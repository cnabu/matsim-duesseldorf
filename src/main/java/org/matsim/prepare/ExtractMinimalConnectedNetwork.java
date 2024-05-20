package org.matsim.prepare;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.locationtech.jts.geom.Polygonal;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.api.core.v01.population.Person;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.options.ShpOptions;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.gbl.MatsimRandom;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.router.costcalculators.FreespeedTravelTimeAndDisutility;
import org.matsim.core.router.costcalculators.TravelDisutilityFactory;
import org.matsim.core.router.speedy.SpeedyALTFactory;
import org.matsim.core.router.util.LeastCostPathCalculator;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.utils.io.IOUtils;
import org.matsim.run.RunDuesseldorfScenario;
import org.matsim.vehicles.Vehicle;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicInteger;

import static org.matsim.run.RunDuesseldorfScenario.VERSION;

/**
 * A set of static methods to mark links in a network created from OSM, then extract these links to a new network.
 *
 * @author Pieter Fourie
 * @author rakow
 */
@CommandLine.Command(
		name = "extract-network",
		description = "Extract most relevant and major links of a network.",
		showDefaultValues = true
)
public class ExtractMinimalConnectedNetwork implements MATSimAppCommand {

	private static final Logger log = LogManager.getLogger(ExtractMinimalConnectedNetwork.class);

	@CommandLine.Parameters(arity = "1", paramLabel = "INPUT", description = "Input network xml", defaultValue = "scenarios/input/duesseldorf-" + VERSION + "-network.xml.gz")
	private List<Path> input;

	@CommandLine.Option(names = "--output", description = "Output network xml", defaultValue = "scenarios/input/duesseldorf-" + VERSION + "-network-filtered.xml.gz")
	private Path output;

	@CommandLine.Mixin
	private ShpOptions shp = new ShpOptions();

	public static void main(String[] args) {
		new ExtractMinimalConnectedNetwork().execute(args);
	}

	@Override
	public Integer call() throws Exception {

		if (shp.getShapeFile() == null) {
			log.error("Shp file is required as input");
			return 2;
		}

		Network inputNetwork = NetworkUtils.readNetwork(input.get(0).toString());

		networkSpatialJoinToBoundaryPolygon(inputNetwork, shp);

		markConnectedLinksOfQualifyingLevelInOSMHierarchy(inputNetwork, ConfigUtils.createConfig(), 1.5, 0.2);

		extractNetworkContainingMarkedLinks(inputNetwork);
		removeDeadEnds(inputNetwork);

		new org.matsim.core.network.algorithms.NetworkCleaner().run(inputNetwork);

		// arb code to write out some values to display using SimWrapper
		NetworkUtils.writeNetwork(inputNetwork, output.toString());

		try (CSVPrinter csv = new CSVPrinter(IOUtils.getBufferedWriter(output.toString().replace(".xml.gz", ".csv")), CSVFormat.DEFAULT)) {
			csv.printRecord("link", "cap");

			for (Link link : inputNetwork.getLinks().values()) {
				if (!link.getAllowedModes().contains(TransportMode.pt))
					csv.printRecord(link.getId(), Math.min(link.getCapacity(), 6000d));
			}

		} catch (IOException e) {
			log.error("Error writing CSV", e);
			return 1;
		}

		return 0;
	}

	/**
	 * Remove nodes without ingoing *and* outgoing links
	 */
	private void removeDeadEnds(Network network) {

		Set<Id<Node>> toRemove = new HashSet<>();

		for (Node node : network.getNodes().values()) {

			if (node.getInLinks().size() == 0 || node.getOutLinks().size() == 0)
				toRemove.add(node.getId());
		}

		log.info("Removing {} dead-ends", toRemove.size());

		toRemove.forEach(network::removeNode);

	}

	/**
	 * Use this to add a link attribute <tt>"keepLink" = true</tt> to network links
	 * lying inside a {@link Polygonal} geometry from the specified shapefile.
	 * <p>
	 * The input network is changed.
	 *
	 * @param network
	 * @param shp
	 */
	public static void networkSpatialJoinToBoundaryPolygon(Network network, ShpOptions shp) {

		ShpOptions.Index index = shp.createIndex(RunDuesseldorfScenario.COORDINATE_SYSTEM, "_");

		network.getNodes().values().forEach(node -> {
			if (index.contains(node.getCoord())) {
				node.getInLinks().values().forEach(link -> link.getAttributes().putAttribute("keepLink", true));
				node.getOutLinks().values().forEach(link -> link.getAttributes().putAttribute("keepLink", true));
			}
		});

		int numberOfIrrelevantLinks = (int) network
				.getLinks()
				.values()
				.stream()
				.filter(link -> {
					Object keepLink = link.getAttributes().getAttribute("keepLink");
					return keepLink != null && (boolean) keepLink;
				}).count();

		log.info("This network has a total of {} links of which {} appear inside the city polygon", network.getLinks().size(), numberOfIrrelevantLinks);
	}

	/**
	 * This creates a duplicate network where all links from the input network that have links with attribute
	 * <tt>"keeplink" = true</tt> are copied to, along wth their connecting nodes. Other methods in this class can be
	 * used to mark such links in a systematic way, or the links can be marked manually.
	 *
	 * @param inputNetwork
	 * @return
	 */
	public static void extractNetworkContainingMarkedLinks(Network inputNetwork) {

		Set<Id<Link>> toRemove = new HashSet<>();

		for (Link link : inputNetwork.getLinks().values()) {
			if (link.getAttributes().getAttribute("keepLink") == null) {
				toRemove.add(link.getId());
			} else
				// clean attribute
				link.getAttributes().removeAttribute("keepLink");
		}

		toRemove.forEach(inputNetwork::removeLink);
	}

	/**
	 * Based on the costs specified in {@link OSMHierarchyTravelDisutility}, takes the set of links with cost lower than
	 * <tt>maxOSMLinkTypeCost</tt>, shuffles them, reduces the set size to <tt>current size x sampleRate</tt>
	 * distributes the resulting set of links across available cores and processes each subset as follows.
	 * <p>
	 * The subset is divided into two, then a pair of qualifying links is produced by taking elements in each set in
	 * sequence. A path between the two links is calculated using {@link OSMHierarchyFavouringFastestPathCalculator},
	 * and all the links in the path, as well as links in the opposite direction, are given an attribute
	 * <tt>"keepLink" = true</tt>.
	 *
	 * @param network
	 * @param config
	 * @param maxOSMLinkTypeCost
	 * @param sampleRate
	 */
	public static void markConnectedLinksOfQualifyingLevelInOSMHierarchy(Network network, Config config,
	                                                                     double maxOSMLinkTypeCost,
	                                                                     double sampleRate) {
		Set<Id<Link>> linkstoKeep = new ConcurrentSkipListSet<>();
		AtomicInteger i = new AtomicInteger(0);

		network.getLinks()
				.values()
				.stream()
				.filter(
						link -> OSMHierarchyTravelDisutility.getOSMLinkTypeCost(link) <= maxOSMLinkTypeCost &&
								!link.getAllowedModes().contains(TransportMode.pt))
				.forEach(link -> linkstoKeep.add(link.getId()));


		class Runner implements Callable<Integer> {

			final OSMHierarchyFavouringFastestPathCalculator pathCalculator = new OSMHierarchyFavouringFastestPathCalculator(network, config);
			final List<Id<Link>> myOriginLinks;
			final List<Id<Link>> myDestinationLinks;

			Runner(List<Id<Link>> myLinks) {
				this.myOriginLinks = myLinks.subList(0, myLinks.size() / 2);
				this.myDestinationLinks = myLinks.subList(myLinks.size() / 2, myLinks.size());
			}

			@Override
			public Integer call() {
				for (int j = 0; j < Math.min(myOriginLinks.size(), myDestinationLinks.size()); j++) {
					Id<Link> link = myOriginLinks.get(j);
					Id<Link> dlink = myDestinationLinks.get(j);
					LeastCostPathCalculator.Path path = pathCalculator.getPath(network.getLinks().get(link).getToNode(),
							network.getLinks().get(dlink).getFromNode());
					if (path == null) {
						log.warn("No route found for {} and {}", link, dlink);
						continue;
					}

					for (Link pathLink : path.links) {
						linkstoKeep.add(pathLink.getId());
					}
					//checking all possible combinations
					path = pathCalculator.getPath(network.getLinks().get(link).getFromNode(),
							network.getLinks().get(dlink).getToNode());
					if (path == null) {
						log.warn("No route found for {} and {}", link, dlink);
						continue;
					}

					for (Link pathLink : path.links) {
						linkstoKeep.add(pathLink.getId());
					}
					path = pathCalculator.getPath(network.getLinks().get(link).getFromNode(),
							network.getLinks().get(dlink).getFromNode());
					if (path == null) {
						log.warn("No route found for {} and {}", link, dlink);
						continue;
					}

					for (Link pathLink : path.links) {
						linkstoKeep.add(pathLink.getId());
					}
					path = pathCalculator.getPath(network.getLinks().get(link).getToNode(),
							network.getLinks().get(dlink).getToNode());
					if (path == null) {
						log.warn("No route found for {} and {}", link, dlink);
						continue;
					}

					for (Link pathLink : path.links) {
						linkstoKeep.add(pathLink.getId());
					}

					int localI = i.incrementAndGet();
					// pieter jan 22: this is only to get some indication of progress, so not logging it.
					if (localI % 100 == 0)
						System.out.print(String.format("%06d:%06d...%s", i.get(), linkstoKeep.size(),
								(localI % 1000 == 0 ?
										"\n" :
										"")));
				}

				return 0;
			}
		}

		int startIndex = 0;
		List<List<Id<Link>>> linkLists = new ArrayList<>();

		List<Id<Link>> links = new ArrayList<>();
		links.addAll(linkstoKeep);
		Collections.shuffle(links);
		Random random = MatsimRandom.getRandom();
		double targetSize = sampleRate * (double) links.size();
		while (links.size() > targetSize)
			links.remove(random.nextInt(links.size()));
		while (startIndex < links.size()) {
			linkLists.add(links.subList(startIndex, Math.min(startIndex + 49, links.size())));
			startIndex += 49;
		}
		// pieter jan 22: if something goes wrong, run the set in a single thread and debug with
		// new Runner(links).run();

		linkLists.parallelStream().forEach(linkList -> {
			new Runner(linkList).call();
		});

		linkstoKeep.forEach(linkId -> {
			Link link = network.getLinks().get(linkId);
			link.getAttributes().putAttribute(
					"keepLink", true);
			Link linkInOppositeDirection = NetworkUtils.findLinkInOppositeDirection(link);
			if (linkInOppositeDirection != null)
				linkInOppositeDirection.getAttributes().putAttribute(
						"keepLink", true);
		});
	}

	/**
	 * Finds the path between two nodes, favouring travel time and level of OSM hierarchy, with links lower in the
	 * hierarchy incurring increasing penalties.
	 */
	private static class OSMHierarchyFavouringFastestPathCalculator {
		final Network network;
		final Config config;

		final LeastCostPathCalculator lcpc;

		final static ThreadLocal<LeastCostPathCalculator> var = new ThreadLocal<>();

		// define how the travel disutility is computed:

		OSMHierarchyFavouringFastestPathCalculator(Network network, Config config) {
			this.network = network;
			this.config = config;

			// Cache this instance for each thread
			if (var.get() == null) {

				TravelDisutilityFactory disutilityFactory = new OSMHierarchyTravelDisutilityFactory();
				TravelTime travelTime = new FreespeedTravelTimeAndDisutility(config.planCalcScore());

				var.set(new SpeedyALTFactory().createPathCalculator(network,
						disutilityFactory.createTravelDisutility(travelTime), travelTime
				));
			}

			lcpc = var.get();
		}

		LeastCostPathCalculator.Path getPath(Node fromNode, Node toNode) {

			return lcpc.calcLeastCostPath(fromNode, toNode, 0, null, null);
		}

	}

	/**
	 * Helper class to {@link OSMHierarchyFavouringFastestPathCalculator}
	 */
	private static class OSMHierarchyTravelDisutilityFactory implements TravelDisutilityFactory {

		@Override
		public TravelDisutility createTravelDisutility(TravelTime timeCalculator) {
			return new OSMHierarchyTravelDisutility(timeCalculator);
		}
	}

	/**
	 * Helper class to {@link OSMHierarchyFavouringFastestPathCalculator}, where a disutility factor is associated with
	 * each level of the OSM hierarchy observed in the Duesseldorf scenario.
	 * <p>
	 * The map of disutilities is pretty arbitrary, tuned after several runs of
	 * {@link ExtractMinimalConnectedNetwork#markConnectedLinksOfQualifyingLevelInOSMHierarchy(Network, Config, double, double)}
	 * to produce a network providing adequate connection and few orphaned links. May have to be adjusted based on
	 * context, and certainly does not contain the entire hierarchy specified in the <a href="">OSM wiki</a>.
	 */
	private static class OSMHierarchyTravelDisutility implements TravelDisutility {
		final TravelTime travelTime;
		static Map<String, Double> osmHierarchyMap = new HashMap<>();

		static {
			osmHierarchyMap.put("highway.motorway", 1d);
			osmHierarchyMap.put("highway.motorway_link", 1d);
			osmHierarchyMap.put("highway.trunk", 1.1);
			osmHierarchyMap.put("highway.trunk_link", 1.1);
			osmHierarchyMap.put("highway.primary", 1.21);
			osmHierarchyMap.put("highway.primary_link", 1.21);
			osmHierarchyMap.put("highway.primary|railway.tram", 1.21);
			osmHierarchyMap.put("highway.secondary", 1.331);
			osmHierarchyMap.put("highway.secondary_link", 1.331);
			osmHierarchyMap.put("highway.secondary|railway.tram", 1.331);
			osmHierarchyMap.put("highway.tertiary", 1.4641);
			osmHierarchyMap.put("highway.tertiary|railway.tram", 1.4641);
			osmHierarchyMap.put("highway.unclassified", 1.61);
			osmHierarchyMap.put("highway.residential", 1.772);
			osmHierarchyMap.put("highway.residential|railway.tram", 1.772);
			osmHierarchyMap.put("highway.living_street", 1.949);
			osmHierarchyMap.put("ZZZ", 2.14);
		}

		OSMHierarchyTravelDisutility(TravelTime travelTime) {
			this.travelTime = travelTime;
		}

		@Override
		public double getLinkTravelDisutility(Link link, double time, Person person, Vehicle vehicle) {
			return getLinkMinimumTravelDisutility(link);
		}

		@Override
		public double getLinkMinimumTravelDisutility(Link link) {
			double linkTypeCost = getOSMLinkTypeCost(link);
			return travelTime.getLinkTravelTime(link, 0d, null, null) * linkTypeCost;
		}

		static double getOSMLinkTypeCost(Link link) {
			String type = getOSMLinkType(link);
			double linkTypeCost = osmHierarchyMap.get(type);
			return linkTypeCost;
		}

		static String getOSMLinkType(Link link) {
			Object type = link.getAttributes().getAttribute("type");
			return type == null ? "ZZZ" : type.toString();
		}
	}
}


