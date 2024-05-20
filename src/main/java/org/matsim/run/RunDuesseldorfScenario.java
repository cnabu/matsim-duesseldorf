package org.matsim.run;

import ch.sbb.matsim.routing.pt.raptor.SwissRailRaptorModule;
import com.google.common.collect.Sets;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.multibindings.Multibinder;
import it.unimi.dsi.fastutil.Pair;
import it.unimi.dsi.fastutil.doubles.Double2DoubleMap;
import it.unimi.dsi.fastutil.doubles.Double2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import it.unimi.dsi.fastutil.objects.Object2DoubleMap;
import it.unimi.dsi.fastutil.objects.Object2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.analysis.ACVModel;
import org.matsim.analysis.AVModel;
import org.matsim.analysis.ModeChoiceCoverageControlerListener;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.application.MATSimApplication;
import org.matsim.application.analysis.CheckPopulation;
import org.matsim.application.analysis.DefaultAnalysisMainModeIdentifier;
import org.matsim.application.analysis.emissions.AirPollutionByVehicleCategory;
import org.matsim.application.analysis.emissions.AirPollutionSpatialAggregation;
import org.matsim.application.analysis.noise.NoiseAnalysis;
import org.matsim.application.analysis.traffic.LinkStats;
import org.matsim.application.analysis.travelTimeValidation.TravelTimeAnalysis;
import org.matsim.application.analysis.travelTimeValidation.TravelTimePatterns;
import org.matsim.application.options.SampleOptions;
import org.matsim.application.prepare.freight.tripExtraction.ExtractRelevantFreightTrips;
import org.matsim.application.prepare.population.*;
import org.matsim.application.prepare.pt.CreateTransitScheduleFromGtfs;
import org.matsim.contrib.signals.otfvis.OTFVisWithSignalsLiveModule;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.Config;
import org.matsim.core.config.groups.*;
import org.matsim.core.config.groups.PlanCalcScoreConfigGroup.ActivityParams;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.mobsim.qsim.AbstractQSimModule;
import org.matsim.core.mobsim.qsim.qnetsimengine.ConfigurableQNetworkFactory;
import org.matsim.core.mobsim.qsim.qnetsimengine.QNetworkFactory;
import org.matsim.core.replanning.strategies.DefaultPlanStrategiesModule;
import org.matsim.core.router.AnalysisMainModeIdentifier;
import org.matsim.prepare.*;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

@CommandLine.Command(header = ":: Open Düsseldorf Scenario ::", version = RunDuesseldorfScenario.VERSION)
@MATSimApplication.Prepare({
	CreateNetwork.class, CreateTransitScheduleFromGtfs.class, CreateCityCounts.class, CleanPopulation.class,
	ExtractEvents.class, CreateBAStCounts.class, TrajectoryToPlans.class, ExtractRelevantFreightTrips.class,
	GenerateShortDistanceTrips.class, MergePopulations.class, DownSamplePopulation.class, ResolveGridCoordinates.class,
	ExtractHomeCoordinates.class, ExtractMinimalConnectedNetwork.class, AdjustPopulationForCutout.class
})
@MATSimApplication.Analysis({
	CheckPopulation.class, AirPollutionByVehicleCategory.class, AirPollutionSpatialAggregation.class,
	LinkStats.class, NoiseAnalysis.class, TravelTimeAnalysis.class, TravelTimePatterns.class
})
public class RunDuesseldorfScenario extends MATSimApplication {

	public static final String VERSION = "v1.7";
	public static final String COORDINATE_SYSTEM = "EPSG:25832";
	public static final double[] X_EXTENT = new double[]{290_000.00, 400_000.0};
	public static final double[] Y_EXTENT = new double[]{5_610_000.00, 5_722_000.00};
	private static final Logger log = LogManager.getLogger(RunDuesseldorfScenario.class);

	@CommandLine.ArgGroup(exclusive = false, heading = "Flow capacity models\n")
	private final VehicleShare vehicleShare = new VehicleShare();

	@CommandLine.ArgGroup(exclusive = false, heading = "Policy options\n")
	private final Policy policy = new Policy();

	@CommandLine.Option(names = "--otfvis", defaultValue = "false", description = "Enable OTFVis live view")
	private boolean otfvis;

	@CommandLine.Mixin
	private SampleOptions sample = new SampleOptions(1, 10, 25);

	@CommandLine.Option(names = {"--dc"}, defaultValue = "1.14", description = "Correct demand by downscaling links.")
	private double demandCorrection;

	@CommandLine.Option(names = {"--lanes"}, defaultValue = "false", negatable = true, description = "Deactivate the use of lane information.")
	private boolean withLanes;

	@CommandLine.Option(names = {"--lane-capacity"}, description = "CSV file with lane capacities.", required = false)
	private Path laneCapacity;

	@CommandLine.Option(names = {"--capacity-factor"}, defaultValue = "1", description = "Scale lane capacity by this factor.")
	private double capacityFactor;

	@CommandLine.Option(names = {"--no-capacity-reduction"}, defaultValue = "false", description = "Disable reduction of flow capacity for taking turns.")
	private boolean noCapacityReduction;

	@CommandLine.Option(names = {"--free-flow"}, defaultValue = "1", description = "Scale up free flow speed of slow links.")
	private double freeFlowFactor;

	@CommandLine.Option(names = "--no-mc", defaultValue = "false", description = "Disable mode choice as replanning strategy.")
	private boolean noModeChoice;

	public RunDuesseldorfScenario() {
		super("scenarios/input/duesseldorf-v1.0-1pct.config.xml");
	}

	public RunDuesseldorfScenario(Config config) {
		super(config);
	}

	public static void main(String[] args) {
		MATSimApplication.run(RunDuesseldorfScenario.class, args);
	}

	@Override
	protected Config prepareConfig(Config config) {
		for (long ii = 600; ii <= 97200; ii += 600) {
			for (String act : List.of("home", "restaurant", "other", "visit", "errands", "educ_higher", "educ_secondary")) {
				config.planCalcScore().addActivityParams(new PlanCalcScoreConfigGroup.ActivityParams(act + "_" + ii).setTypicalDuration(ii));
			}

			config.planCalcScore().addActivityParams(new ActivityParams("work_" + ii).setTypicalDuration(ii).setOpeningTime(6. * 3600.).setClosingTime(20. * 3600.));
			config.planCalcScore().addActivityParams(new ActivityParams("business_" + ii).setTypicalDuration(ii).setOpeningTime(6. * 3600.).setClosingTime(20. * 3600.));
			config.planCalcScore().addActivityParams(new ActivityParams("leisure_" + ii).setTypicalDuration(ii).setOpeningTime(9. * 3600.).setClosingTime(27. * 3600.));
			config.planCalcScore().addActivityParams(new ActivityParams("shopping_" + ii).setTypicalDuration(ii).setOpeningTime(8. * 3600.).setClosingTime(20. * 3600.));
		}

		if (sample.getSize() != 1) {
			config.plans().setInputFile(sample.adjustName(config.plans().getInputFile()));
			config.controler().setRunId(sample.adjustName(config.controler().getRunId()));
			config.controler().setOutputDirectory(sample.adjustName(config.controler().getOutputDirectory()));

			config.qsim().setFlowCapFactor(sample.getSize() / (100.0 * demandCorrection));
			config.qsim().setStorageCapFactor(sample.getSize() / (100.0 * demandCorrection));
		}

		if (demandCorrection != 1.0)
			addRunOption(config, "dc", demandCorrection);

		config.controler().setLinkToLinkRoutingEnabled(false);
		config.network().setLaneDefinitionsFile(null);
		config.travelTimeCalculator().setCalculateLinkToLinkTravelTimes(false);
		config.controler().setRoutingAlgorithmType(ControlerConfigGroup.RoutingAlgorithmType.SpeedyALT);

		if (withLanes)
			throw new IllegalArgumentException("The argument --no-lanes/--lanes is deprecated, please remove it.");

		if (capacityFactor != 1.0)
			addRunOption(config, "cap", capacityFactor);

		if (freeFlowFactor != 1)
			addRunOption(config, "ff", freeFlowFactor);

		if (noModeChoice) {
			config.controler().setLastIteration((int) (config.controler().getLastIteration() * 0.6));

			List<StrategyConfigGroup.StrategySettings> strategies = config.strategy().getStrategySettings().stream()
				.filter(s -> !s.getStrategyName().equals(DefaultPlanStrategiesModule.DefaultStrategy.SubtourModeChoice) &&
					!s.getStrategyName().equals(DefaultPlanStrategiesModule.DefaultStrategy.ChangeSingleTripMode)).collect(Collectors.toList());

			config.strategy().clearStrategySettings();
			strategies.forEach(s -> {
				if (s.getStrategyName().equals("ReRoute"))
					s.setDisableAfter((int) (config.controler().getLastIteration() * 0.9));
				else if (s.getDisableAfter() > 0 && s.getDisableAfter() != Integer.MAX_VALUE)
					s.setDisableAfter((int) (0.8 * s.getDisableAfter()));
			});

			strategies.forEach(s -> config.strategy().addStrategySettings(s));

			addRunOption(config, "noMc");

			log.info("Number of iterations reduced automatically by using no mode choice: {}", config.controler().getLastIteration());
		}

		if (noCapacityReduction)
			addRunOption(config, "no-cap-red");

		config.planCalcScore().addActivityParams(new ActivityParams("car interaction").setTypicalDuration(60));
		config.planCalcScore().addActivityParams(new ActivityParams("other").setTypicalDuration(600 * 3));
		config.planCalcScore().addActivityParams(new ActivityParams("freight_start").setTypicalDuration(60 * 15));
		config.planCalcScore().addActivityParams(new ActivityParams("freight_end").setTypicalDuration(60 * 15));

		config.vspExperimental().setVspDefaultsCheckingLevel(VspExperimentalConfigGroup.VspDefaultsCheckingLevel.info);
		config.plansCalcRoute().setAccessEgressType(PlansCalcRouteConfigGroup.AccessEgressType.accessEgressModeToLink);
		config.qsim().setUsingTravelTimeCheckInTeleportation(true);
		config.plans().setHandlingOfPlansWithoutRoutingMode(PlansConfigGroup.HandlingOfPlansWithoutRoutingMode.useMainModeIdentifier);

		return config;
	}

	@Override
	protected void prepareScenario(Scenario scenario) {
		// Call network modification before existing setup
		modifyNetwork(scenario.getNetwork());

		super.prepareScenario(scenario);

		Map<Id<Link>, ? extends Link> links = scenario.getNetwork().getLinks();
		Object2DoubleMap<Pair<Id<Link>, Id<Link>>> capacities = new Object2DoubleOpenHashMap<>();

		if (laneCapacity != null) {
			capacities = CreateNetwork.readLinkCapacities(laneCapacity);
			log.info("Overwrite capacities from {}, containing {} links", laneCapacity, capacities.size());

			int n = CreateNetwork.setLinkCapacities(scenario.getNetwork(), capacities, null);
			log.info("Unmatched links: {}", n);
		}

		if (vehicleShare.av > 0 || vehicleShare.acv > 0) {
			if (vehicleShare.av > 0 && vehicleShare.acv > 0)
				throw new IllegalArgumentException("Only one of ACV or AV can be greater 0!");

			log.info("Applying model AV {} ACV {} to road capacities", vehicleShare.av, vehicleShare.acv);
			Set<Id<Link>> ids = capacities.keySet().stream().map(Pair::left).collect(Collectors.toSet());
			Double2DoubleMap factors = new Double2DoubleOpenHashMap();

			for (Link link : links.values()) {
				if (ids.contains(link.getId()))
					continue;
				if (link.getAttributes().getAttribute("allowed_speed") == null)
					continue;

				double cap = 1d;
				if (vehicleShare.av > 0)
					cap = factors.computeIfAbsent((double) link.getAttributes().getAttribute("allowed_speed"), s -> AVModel.score(s, vehicleShare.av / 100d));
				else
					cap = factors.computeIfAbsent((double) link.getAttributes().getAttribute("allowed_speed"), s -> ACVModel.score(s, vehicleShare.acv / 100d));

				link.setCapacity(link.getCapacity() * cap);
			}
			log.trace("Done");
		}

		Object2IntMap<Id<Link>> linkFilter = new Object2IntOpenHashMap<>();
		Set<Id<Link>> noCar = new HashSet<>();

		if (policy.carFilter != null) {
			try (CSVParser parser = new CSVParser(Files.newBufferedReader(policy.carFilter), CSVFormat.DEFAULT.withFirstRecordAsHeader())) {
				for (CSVRecord record : parser) {
					noCar.add(Id.createLinkId(record.get("ID")));
				}
			} catch (IOException e) {
				throw new IllegalStateException("Could not read csv", e);
			}
			log.info("Reading car filter from {} with {} links", policy.carFilter, noCar.size());
		}

		if (policy.linkFilter != null) {
			log.info("Reading link filter from {}", policy.linkFilter);
			try (CSVParser parser = new CSVParser(Files.newBufferedReader(policy.linkFilter), CSVFormat.DEFAULT.withFirstRecordAsHeader())) {
				for (CSVRecord record : parser) {
					linkFilter.put(Id.createLinkId(record.get("ID")), Integer.parseInt(record.get("corridor")));
				}
			} catch (IOException e) {
				throw new IllegalStateException("Could not read csv", e);
			}

			double factor = 1;
			if (policy.capacity != null) {
				Object2DoubleMap<Pair<Id<Link>, Id<Link>>> newCapacities = CreateNetwork.readLinkCapacities(policy.capacity);
				newCapacities.keySet().removeIf(e -> !linkFilter.containsKey(e.key()));

				log.info("Policy capacities from {}, containing {} links", policy.capacity, newCapacities.size());
				if (capacities.isEmpty())
					throw new IllegalStateException("Policy requires the base capacities to be set.");

				DoubleList rel = new DoubleArrayList();
				for (Object2DoubleMap.Entry<Pair<Id<Link>, Id<Link>>> e : newCapacities.object2DoubleEntrySet()) {
					rel.add(e.getDoubleValue() / capacities.getDouble(e.getKey()));
				}
				factor = rel.doubleStream().average().orElseThrow();
				log.info("Capacity increase factor is {}", factor);

				int n = CreateNetwork.setLinkCapacities(scenario.getNetwork(), newCapacities, new HashSet<>());
				log.info("Unmatched links: {}", n);
			}
			CreateNetwork.reduceLinkLanesAndMultiplyPerLaneCapacity(scenario.getNetwork(), linkFilter, factor, policy.laneReduction);
		}

		for (Link link : links.values()) {
			if (link.getFreespeed() < 25.5 / 3.6) {
				link.setFreespeed(link.getFreespeed() * freeFlowFactor);
			}

			if (link.getAttributes().getAttribute("junction") == Boolean.TRUE || "traffic_light".equals(link.getToNode().getAttributes().getAttribute("type"))) {
				if (capacityFactor != 1 && (linkFilter.isEmpty() || linkFilter.containsKey(link.getId()))) {
					log.debug("Setting capacity for link: {}", link);
					link.setCapacity(link.getCapacity() * capacityFactor);
				}
			}

			Set<String> modes = link.getAllowedModes();
			if (modes.contains("car")) {
				HashSet<String> newModes = Sets.newHashSet(modes);
				newModes.add("freight");
				link.setAllowedModes(newModes);
			}

			if (noCar.contains(link.getId())) {
				link.setFreespeed(15 / 3.6);
				link.setCapacity(300);
			}
		}
	}

	private void modifyNetwork(Network network) {
		Set<Id<Link>> eligibleLinks = new HashSet<>();

		for (Link link : network.getLinks().values()) {
			double freespeed = link.getFreespeed();
			double capacity = link.getCapacity();

			if (freespeed >= 14.0 / 3.6 && freespeed <= 30.0 / 3.6 && capacity >= 600.0 && capacity <= 1000.0) {
				eligibleLinks.add(link.getId());
			}
		}

		Random random = new Random();
		int count = 0;
		for (Id<Link> linkId : eligibleLinks) {
			if (random.nextDouble() < 0.5) {    // Stochastic selection: Modify 50% of eligible links
				Link link = network.getLinks().get(linkId);
				link.setFreespeed(15.0 / 3.6);  // Convert km/h to m/s
				link.setCapacity(600.0);
				count++;
			}
		}
		System.out.println("Modified " + count + " links to reduced freespeed and capacity.");
	}

	@Override
	protected void prepareControler(Controler controler) {
		if (otfvis)
			controler.addOverridingModule(new OTFVisWithSignalsLiveModule());

		controler.addOverridingModule(new AbstractModule() {
			@Override
			public void install() {
				install(new SwissRailRaptorModule());
				addControlerListenerBinding().to(ModeChoiceCoverageControlerListener.class);
				bind(AnalysisMainModeIdentifier.class).to(DefaultAnalysisMainModeIdentifier.class);

				addControlerListenerBinding().to(StrategyWeightFadeout.class).in(Singleton.class);

				Multibinder<StrategyWeightFadeout.Schedule> schedules = Multibinder.newSetBinder(binder(), StrategyWeightFadeout.Schedule.class);

				if (noModeChoice) {
					schedules.addBinding().toInstance(new StrategyWeightFadeout.Schedule(DefaultPlanStrategiesModule.DefaultStrategy.ReRoute, "person", 0.6));
				} else {
					schedules.addBinding().toInstance(new StrategyWeightFadeout.Schedule(DefaultPlanStrategiesModule.DefaultStrategy.SubtourModeChoice, "person", 0.65, 0.85));
					schedules.addBinding().toInstance(new StrategyWeightFadeout.Schedule(DefaultPlanStrategiesModule.DefaultStrategy.ReRoute, "person", 0.78));
				}
			}
		});

		controler.addOverridingQSimModule(new AbstractQSimModule() {
			@Override
			protected void configureQSim() {
			}

			@Provides
			QNetworkFactory provideQNetworkFactory(EventsManager eventsManager, Scenario scenario) {
				ConfigurableQNetworkFactory factory = new ConfigurableQNetworkFactory(eventsManager, scenario);

				TurnDependentFlowEfficiencyCalculator fe;
				if (!noCapacityReduction) {
					fe = new TurnDependentFlowEfficiencyCalculator(scenario);
					factory.setFlowEfficiencyCalculator(fe);
				}

				return factory;
			}
		});
	}

	static final class VehicleShare {
		@CommandLine.Option(names = "--av", defaultValue = "0", description = "Percentage of automated vehicles. [0, 100]")
		int av;

		@CommandLine.Option(names = "--acv", defaultValue = "0", description = "Percentage of autonomous connected vehicles. [0, 100]")
		int acv;
	}

	static final class Policy {
		@CommandLine.Option(names = {"--link-filter"}, description = "CSV file with links to filter the provided link capacities from SUMO.", required = false)
		private Path linkFilter;

		@CommandLine.Option(names = {"--car-filter"}, description = "CSV file with links to exclude car and freight.", required = false)
		private Path carFilter;

		@CommandLine.Option(names = {"--lane-reduction"}, defaultValue = "0", description = "Lanes in --link-filter will be reduced by lane.", required = false)
		private double laneReduction;

		@CommandLine.Option(names = {"--policy-capacity"}, description = "CSV file with lane capacities for link-filter.", required = false)
		private Path capacity;
	}
}
