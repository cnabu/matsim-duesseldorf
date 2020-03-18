package org.matsim.prepare;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.NetworkWriter;
import org.matsim.contrib.gtfs.GtfsConverter;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.geometry.CoordinateTransformation;
import org.matsim.core.utils.geometry.transformations.TransformationFactory;
import org.matsim.pt.transitSchedule.api.TransitScheduleWriter;
import org.matsim.pt.utils.CreatePseudoNetwork;
import org.matsim.pt.utils.CreateVehiclesForSchedule;
import org.matsim.run.RunDuesseldorfScenario;
import org.matsim.vehicles.MatsimVehicleWriter;
import picocli.CommandLine;

import java.io.File;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.concurrent.Callable;

/**
 * This script utilizes GTFS2MATSim and creates a pseudo network and vehicles using MATSim standard API functionality.
 *
 * @author rakow
 */
@CommandLine.Command(
        name = "transit",
        description = "Create transit schedule from GTFS data",
        showDefaultValues = true
)
public class CreateTransitSchedule implements Callable<Integer> {

    @CommandLine.Parameters(arity = "1", paramLabel = "INPUT", description = "Input GTFS zip file", defaultValue = "scenarios/input/gtfs.zip")
    private Path gtfsZipFile;

    @CommandLine.Option(names = "--output", description = "Output folder", defaultValue = "scenarios/input")
    private File output;

    @CommandLine.Option(names = "--input-cs", description = "Input coordinate system of the data", defaultValue = TransformationFactory.WGS84)
    private String inputCS;

    @CommandLine.Option(names = "--target-cs", description = "Target coordinate system of the network", defaultValue = RunDuesseldorfScenario.COORDINATE_SYSTEM)
    private String targetCS;

    @CommandLine.Option(names = "--date", description = "The day for which the schedules will be extracted", defaultValue = "2020-03-09")
    private LocalDate date;


    @Override
    public Integer call() throws Exception {

        CoordinateTransformation ct = TransformationFactory.getCoordinateTransformation(inputCS, targetCS);

        // Output files
        File scheduleFile = new File(output, "transitSchedule.xml.gz");
        File networkFile = new File(output, "network-with-pt.xml.gz");
        File transitVehiclesFile = new File(output, "transitVehicles.xml.gz");

        Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());

        GtfsConverter converter = GtfsConverter.newBuilder()
                .setScenario(scenario)
                .setTransform(ct)
                .setDate(date)
                .setFeed(gtfsZipFile)
                .setIncludeAgency(agency -> agency.equals("rbg-70"))
                .setFilterStops(stop -> {
                    Coord coord = ct.transform(new Coord(stop.stop_lon, stop.stop_lat));
                    return coord.getX() >= RunDuesseldorfScenario.X_EXTENT[0] && coord.getX() <= RunDuesseldorfScenario.X_EXTENT[1] &&
                            coord.getY() >= RunDuesseldorfScenario.Y_EXTENT[0] && coord.getY() <= RunDuesseldorfScenario.Y_EXTENT[1];
                })
                .build();

        converter.convert();

        // TODO: filter more irrelevant pt

        // Create a network around the schedule
        new CreatePseudoNetwork(scenario.getTransitSchedule(), scenario.getNetwork(), "pt_").createNetwork();
		new CreateVehiclesForSchedule(scenario.getTransitSchedule(), scenario.getTransitVehicles()).run();

        new TransitScheduleWriter(scenario.getTransitSchedule()).writeFile(scheduleFile.getAbsolutePath());
        new NetworkWriter(scenario.getNetwork()).write(networkFile.getAbsolutePath());
        new MatsimVehicleWriter(scenario.getTransitVehicles()).writeFile(transitVehiclesFile.getAbsolutePath());

        return 0;
    }


    public static void main(String[] args) {
        System.exit(new CommandLine(new CreateTransitSchedule()).execute(args));
    }

}
