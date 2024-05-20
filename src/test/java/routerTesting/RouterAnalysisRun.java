package routerTesting;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.router.FastAStarLandmarksFactory;
import org.matsim.core.router.costcalculators.RandomizingTimeDistanceTravelDisutilityFactory;
import org.matsim.core.router.util.LeastCostPathCalculator;
import org.matsim.core.router.util.LeastCostPathCalculator.Path;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.scenario.ScenarioUtils;

import java.util.Map;

public class RouterAnalysisRun {
	private final static String CONFIG_FILE = "C:\\Users\\cluac\\MATSimScenarios\\Dusseldorf\\Scenario\\duesseldorf-v1.2-10pct.config.xml";
	private final static String EVENTS_FILE = "C:\\Users\\cluac\\MATSimScenarios\\Dusseldorf\\output\\v1.2-10pct-01\\duesseldorf-10pct-no-lanes.output_events.xml.gz";

	// Route to calculate
	private final static String[][] LINK_PAIRS = { { "5098457#2", "12152142", "36000" },
			{ "5098457#2", "12152142", "1800" }, { "-288980669", "41131496#1", "61200" } };

	public static void main(String[] args) {
		Config config = ConfigUtils.loadConfig(CONFIG_FILE);
		Scenario scenario = ScenarioUtils.loadScenario(config);
		Network network = scenario.getNetwork();

		RouterAnalysisWithTraffic routerAnalysisWithTraffic = new RouterAnalysisWithTraffic(EVENTS_FILE, network);
		Map<Double, Map<String, Double>> linkTravelTimeMap = routerAnalysisWithTraffic.processEventsFile();
		RouterTestingTravelTime travelTime = new RouterTestingTravelTime(linkTravelTimeMap, network);

		FastAStarLandmarksFactory fastAStarLandmarksFactory = new FastAStarLandmarksFactory(8);
		RandomizingTimeDistanceTravelDisutilityFactory disutilityFactory = new RandomizingTimeDistanceTravelDisutilityFactory(
				"car", config);
		TravelDisutility travelDisutility = disutilityFactory.createTravelDisutility(travelTime);
		LeastCostPathCalculator router = fastAStarLandmarksFactory.createPathCalculator(network, travelDisutility,
				travelTime);

		for (int i = 0; i < LINK_PAIRS.length; i++) {
			Id<Link> fromLinkId = Id.create(LINK_PAIRS[i][0], Link.class);
			Id<Link> toLinkId = Id.create(LINK_PAIRS[i][1], Link.class);
			Link fromLink = network.getLinks().get(fromLinkId);
			Link toLink = network.getLinks().get(toLinkId);
			Path route = router.calcLeastCostPath(fromLink.getToNode(), toLink.getToNode(),
					Double.parseDouble(LINK_PAIRS[i][2]), null, null);
			System.out.println("Travel info from " + LINK_PAIRS[i][0] + " to " + LINK_PAIRS[i][1] + " at time="
					+ LINK_PAIRS[i][2] + " is as follow: ");
			System.out.println("Total travel time is " + Double.toString(route.travelTime) + "s");
			System.out.println("Total travel cost is " + Double.toString(route.travelCost));
			System.out.println("The route is: ");
			for (Link link : route.links) {
				System.out.println(link.getId().toString());
			}

		}

	}
}
