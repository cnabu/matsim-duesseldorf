package org.matsim.run;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.NetworkWriter;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

public class ModifyDuesseldorfNetwork {

	public static void main(String[] args) {
		String networkFilePath = "C:/Users/omkarp/IdeaProjects/matsim-duesseldorf/scenarios/input/duesseldorf-v1.7-network-with-pt.xml.gz";
		String modifiedNetworkFilePath = "C:/Users/omkarp/IdeaProjects/matsim-duesseldorf/scenarios/input/duesseldorf-v1.7-network-with-pt-modified.xml.gz";

		// Load the network
		Config config = ConfigUtils.createConfig();
		config.network().setInputFile(networkFilePath);
		Scenario scenario = ScenarioUtils.loadScenario(config);
		Network network = scenario.getNetwork();

		// Modify the network
		modifyNetwork(network);

		// Ensure the CRS is correctly set
		network.getAttributes().putAttribute("coordinateReferenceSystem", "EPSG:25832");

		// Save the modified network
		new NetworkWriter(network).write(modifiedNetworkFilePath);
		System.out.println("Modified network saved to " + modifiedNetworkFilePath);
	}

	private static void modifyNetwork(Network network) {
		Set<Id<Link>> eligibleLinks = new HashSet<>();

		// Identify eligible links based on freespeed and capacity criteria
		for (Link link : network.getLinks().values()) {
			double freespeed = link.getFreespeed();
			double capacity = link.getCapacity();

			if (freespeed >= 14.0 / 3.6 && freespeed <= 30.0 / 3.6 && capacity >= 600.0 && capacity <= 1000.0) {
				eligibleLinks.add(link.getId());
			}
		}

		// Stochastic selection: Modify 50% of eligible links
		Random random = new Random();
		int count = 0;
		for (Id<Link> linkId : eligibleLinks) {
			if (random.nextDouble() < 0.5) {
				Link link = network.getLinks().get(linkId);
				link.setFreespeed(15.0 / 3.6);  // Convert km/h to m/s
				link.setCapacity(600.0);
				count++;
			}
		}
		System.out.println("Modified " + count + " links to reduced freespeed and capacity.");
	}
}
