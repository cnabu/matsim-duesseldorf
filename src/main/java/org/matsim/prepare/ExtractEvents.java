package org.matsim.prepare;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.events.LinkEnterEvent;
import org.matsim.api.core.v01.events.LinkLeaveEvent;
import org.matsim.api.core.v01.events.handler.LinkEnterEventHandler;
import org.matsim.api.core.v01.events.handler.LinkLeaveEventHandler;
import org.matsim.application.MATSimAppCommand;
import org.matsim.contrib.sumo.SumoNetworkConverter;
import org.matsim.contrib.sumo.SumoNetworkHandler;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.api.experimental.events.LaneEnterEvent;
import org.matsim.core.api.experimental.events.LaneLeaveEvent;
import org.matsim.core.api.experimental.events.handler.LaneEnterEventHandler;
import org.matsim.core.api.experimental.events.handler.LaneLeaveEventHandler;
import org.matsim.core.events.EventsManagerImpl;
import org.matsim.core.events.MatsimEventsReader;
import org.matsim.core.events.algorithms.EventWriterXML;
import org.matsim.core.utils.io.IOUtils;
import org.matsim.lanes.Lane;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Executable class to extract events.
 */
@CommandLine.Command(
        name = "extractEvents",
        description = "Extract events for a certain network area.",
        showDefaultValues = true
)
public class ExtractEvents implements MATSimAppCommand, LinkEnterEventHandler, LinkLeaveEventHandler, LaneEnterEventHandler, LaneLeaveEventHandler {

    private static final Logger log = LogManager.getLogger(ExtractEvents.class);

    @CommandLine.Parameters(arity = "1", paramLabel = "INPUT", description = "Input event file")
    private Path input;

    @CommandLine.Option(names = "--network", description = "Path to the SUMO network to extract events for", required = true)
    private Path network;

    @CommandLine.Option(names = "--output", description = "Path to output file", required = true)
    private Path output;

    @CommandLine.Option(names = "--no-lanes", description = "Don't parse lane events", defaultValue = "false")
    private boolean noLanes;

    private SumoNetworkHandler sumo;
    private List<Event> events;

    public static void main(String[] args) {
        System.exit(new CommandLine(new ExtractEvents()).execute(args));
    }

    @Override
    public Integer call() throws Exception {

        sumo = SumoNetworkConverter.readNetwork(network.toFile());
        events = new ArrayList<>();

        EventsManager manager = new EventsManagerImpl();
        manager.addHandler(this);
        manager.initProcessing();

        MatsimEventsReader reader = new MatsimEventsReader(manager);

        if (!noLanes) {
            reader.addCustomEventMapper(LaneLeaveEvent.EVENT_TYPE, event ->
                    new LaneLeaveEvent(
                            event.getTime(),
                            Id.createVehicleId(event.getAttributes().get(LaneLeaveEvent.ATTRIBUTE_VEHICLE)),
                            Id.createLinkId(event.getAttributes().get(LaneLeaveEvent.ATTRIBUTE_LINK)),
                            Id.create(event.getAttributes().get(LaneLeaveEvent.ATTRIBUTE_LANE), Lane.class)
                    )
            );

            reader.addCustomEventMapper(LaneEnterEvent.EVENT_TYPE, event ->
                    new LaneEnterEvent(
                            event.getTime(),
                            Id.createVehicleId(event.getAttributes().get(LaneEnterEvent.ATTRIBUTE_VEHICLE)),
                            Id.createLinkId(event.getAttributes().get(LaneEnterEvent.ATTRIBUTE_LINK)),
                            Id.create(event.getAttributes().get(LaneEnterEvent.ATTRIBUTE_LANE), Lane.class)
                    )
            );
        }

        reader.readFile(input.toString());
        manager.finishProcessing();

        log.info("Filtered {} events", events.size());

        manager = new EventsManagerImpl();

        if (output.getParent() != null)
            Files.createDirectories(output.getParent());

        EventWriterXML writer = new EventWriterXML(IOUtils.getOutputStream(IOUtils.getFileUrl(output.toString()), false));
        manager.addHandler(writer);

        events.forEach(manager::processEvent);

        writer.closeFile();

        return 0;
    }

    @Override
    public void handleEvent(LinkEnterEvent event) {
        if (sumo.getEdges().containsKey(event.getLinkId().toString()))
            events.add(event);
    }

    @Override
    public void handleEvent(LinkLeaveEvent event) {
        if (sumo.getEdges().containsKey(event.getLinkId().toString()))
            events.add(event);
    }

    @Override
    public void handleEvent(LaneEnterEvent event) {
        if (sumo.getEdges().containsKey(event.getLinkId().toString()) || sumo.getLanes().containsKey(event.getLaneId().toString()))
            events.add(event);
    }

    @Override
    public void handleEvent(LaneLeaveEvent event) {
        if (sumo.getEdges().containsKey(event.getLinkId().toString()) || sumo.getLanes().containsKey(event.getLaneId().toString()))
            events.add(event);
    }
}
