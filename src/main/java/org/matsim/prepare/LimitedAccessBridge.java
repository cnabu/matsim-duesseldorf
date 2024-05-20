package org.matsim.prepare;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.utils.collections.CollectionUtils;

public class LimitedAccessBridge {

	public static void main(String[] args){

		final String[] mode = {"bike"};

		var network = NetworkUtils.readNetwork("C:/Users/arsal/Downloads/duesseldorf-1pct-dc_114.output_network.xml");

		for(Link link : network.getLinks().values()){
			if (link.getId().equals(Id.createLinkId("90115782"))){
				link.setAllowedModes(CollectionUtils.stringArrayToSet(mode));


			}

			NetworkUtils.writeNetwork(network,"C:/Users/arsal/Downloads/changed-network-v1.7-network-with-pt.xml.gz");

		}

	}
}
