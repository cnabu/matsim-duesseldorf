package routerTesting;

import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.router.util.TravelTime;
import org.matsim.vehicles.Vehicle;

import java.util.Map;

public class RouterTestingTravelTime implements TravelTime {
	private final Map<Double, Map<String, Double>> linkTravelTimeMap;
	private final Network network;

	public RouterTestingTravelTime(Map<Double, Map<String, Double>> linkTravelTimeMap, Network network) {
		this.linkTravelTimeMap = linkTravelTimeMap;
		this.network = network;
	}

	@Override
	public double getLinkTravelTime(Link link, double time, Person person, Vehicle vehicle) {
		double timeBin = Math.min(Math.floor(time / RouterAnalysisWithTraffic.TIME_BIN_SIZE),
				RouterAnalysisWithTraffic.TOTAL_TIME_BIN);
		if (linkTravelTimeMap.get(timeBin).containsKey(link.getId().toString())) {
			return linkTravelTimeMap.get(timeBin).get(link.getId().toString());
		}

		double freeSpeed = network.getLinks().get(link.getId()).getFreespeed();
		double length = network.getLinks().get(link.getId()).getLength();
		return Math.floor(length / freeSpeed) + 1;
	}

}
