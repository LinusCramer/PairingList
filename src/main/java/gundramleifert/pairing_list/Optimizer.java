package gundramleifert.pairing_list;

import com.fasterxml.jackson.databind.deser.DataFormatReaders;
import gundramleifert.pairing_list.configs.*;
import gundramleifert.pairing_list.cost_calculators.CostCalculatorBoatSchedule;
import gundramleifert.pairing_list.cost_calculators.CostCalculatorMatchMatrix;
import gundramleifert.pairing_list.cost_calculators.ICostCalculator;
import gundramleifert.pairing_list.types.BoatMatrix;
import gundramleifert.pairing_list.types.Flight;
import gundramleifert.pairing_list.types.Schedule;
import lombok.SneakyThrows;
import org.apache.commons.cli.*;

import java.io.File;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static gundramleifert.pairing_list.cost_calculators.CostCalculatorBoatSchedule.getInterFlightStat;

public class Optimizer {
    private ScheduleConfig properties;
    private OptimizationConfig optProps;
    private Random random;

    public void init(ScheduleConfig properties, OptimizationConfig optimizationConfig, Random random) {
        this.properties = properties;
        this.optProps = optimizationConfig;
        this.random = random;

    }

    private static void printQuality(CostCalculatorMatchMatrix scorer, List<Flight> flights) {
        System.out.format("Score:%6.3f Age:%3d best ( %6.3f worst)\n",
                scorer.score(flights.get(0)),
                flights.get(0).getAge(),
                scorer.score(flights.get(flights.size() - 1)));
    }

    private static void printQuality(String prefix, CostCalculatorMatchMatrix scorer, Flight schedule) {
        System.out.format("Score:%6.3f Age:%3d %s\n", scorer.score(schedule), schedule.getAge(), prefix);
    }

    private static void printQuality(String prefix, ICostCalculator scorer, Schedule schedule) {
        System.out.format("Score:%6.3f Age:%3d %s\n", scorer.score(schedule), schedule.getAge(), prefix);
    }

    private List<Flight> getBestFlights(Schedule schedule, Random random, Consumer<Schedule> saver) {
        List<Flight> flights = new ArrayList<>();
        flights.add(Util.getRandomFlight(properties, random));
        int counter = 0;
        MatchMatrix matchMatrix1 = Util.getMatchMatrix(schedule, properties.numTeams);
        final CostCalculatorMatchMatrix scorer = new CostCalculatorMatchMatrix(properties, matchMatrix1);
        OptMatchMatrixConfig optMatchMatrix = optProps.optMatchMatrix;
        for (int i = 0; i < optMatchMatrix.loops; i++) {
            for (int j = 0; j < optMatchMatrix.swapTeams; j++) {
                Flight mutation = flights.get(random.nextInt(flights.size()));
                mutation = MutationUtil.swapBetweenRaces(mutation, random);
                if (!flights.contains(mutation)) {
                    flights.add(mutation);
                }
            }
            flights.sort(Comparator.comparingDouble(scorer::scoreWithCache));
            if (flights.size() > optMatchMatrix.individuals) {
                   /* for (Schedule schedule : schedules.subList(individuals, schedules.size())) {
                        hashes.remove(Integer.valueOf(schedule.hashCode()));
                    }*/
                flights = flights.subList(0, optMatchMatrix.individuals);
            }
            if (i == optMatchMatrix.loops - 1 || (optMatchMatrix.showEveryN > 0 && counter % optMatchMatrix.showEveryN == 0)) {
                System.out.println("------------  " + i + "  -----------------------");
                //System.out.println("best1:" + scorer1.score(schedules.get(0)));
                printQuality(scorer, flights);
                //Util.printMatchMatrix(properties, schedules.get(0));
                Schedule copy = schedule.copy();
                copy.add(flights.get(0));
//                Util.printCount(properties, copy);
//            }
                for (Flight s : flights) {
                    s.getOlder();
                }
                counter++;
                if (optMatchMatrix.earlyStopping > 0 && flights.get(0).getAge() >= optMatchMatrix.earlyStopping) {
                    System.out.println("Early Stopping applied");
                    break;
                }
                if (saver != null && optMatchMatrix.saveEveryN > 0 && counter % optMatchMatrix.saveEveryN == 0) {
                    Schedule copySchedule = schedule.copy();
                    copySchedule.add(flights.get(0));
                    saver.accept(copySchedule);
                }

            }
        }
//        return flights;
        double currentValue = scorer.scoreWithCache(flights.get(0));
        List<Flight> collect = flights
                .stream()
                .filter(flight -> Math.abs(scorer.scoreWithCache(flight) - currentValue) < 1e-5)
                .collect(Collectors.toList());
        System.out.println(String.format("found %d flights with equal costs = %.3f", collect.size(), currentValue));
        return collect;
    }

    public Schedule optimizeMatchMatrix(Consumer<Schedule> saver) {

        Set<Schedule> schedulesBest = new LinkedHashSet<>();
        Flight flight0 = Util.getRandomFlight(properties, random);
        Schedule startSchedule = new Schedule(properties.numTeams);
        startSchedule.add(flight0);
        schedulesBest.add(startSchedule);
        for (int f = 1; f < this.properties.flights; f++) {
            System.out.println(String.format("Flight %d:", f + 1));
            Set<Schedule> nextSchedules = new LinkedHashSet<>();
            for (Schedule schedule : schedulesBest) {
                List<Flight> bestFlights = getBestFlights(schedule, random, saver);
                for (int j = 0; j < bestFlights.size(); j++) {
                    Schedule scheduleNew = schedule.copy();
                    scheduleNew.add(bestFlights.get(j));
                    nextSchedules.add(scheduleNew);
                }
            }
            CostCalculatorMatchMatrix cc = new CostCalculatorMatchMatrix();
            Schedule min = nextSchedules
                    .stream()
                    .min(Comparator.comparingDouble(cc::score))
                    .orElseThrow(() -> new RuntimeException("empty schedules"));
            double costMin = cc.score(min);
            schedulesBest = nextSchedules
                    .stream()
                    .filter(schedule -> Math.abs(cc.score(schedule) - costMin) < 1e-5)
                    .collect(Collectors.toSet());
            System.out.println(String.format("found %d best schedules for flight %d", schedulesBest.size(), f+1));
            if (schedulesBest.size() > optProps.optMatchMatrix.maxBranches) {
                List<Schedule> collect = new ArrayList<>(schedulesBest);
                Collections.shuffle(collect, random);
                schedulesBest = new LinkedHashSet<>(collect.subList(0, optProps.optMatchMatrix.maxBranches));
            }
            System.out.println("best so far:");
            int ii = 0;
            for (Schedule s : schedulesBest) {
                int[] matchDistribution = s.matchMatrix.getMatchDistribution();
                Util.printCount(matchDistribution, false);
            }
        }
        return schedulesBest.stream().findFirst().orElseThrow(() -> new RuntimeException("empty list"));
    }

    public Schedule optimizeBoatSchedule(Schedule schedule, Consumer<Schedule> saver) {
        List<Schedule> schedules = new ArrayList<>();
        schedules.add(schedule);
        int counter = 0;
        for (OptBoatConfig optBoatUsage : optProps.optBoatUsage) {
            System.out.println(String.format("run with %s", optBoatUsage));
            for (Schedule s : schedules) {
                s.resetAge();
            }
            final CostCalculatorBoatSchedule scorer = new CostCalculatorBoatSchedule(properties, optBoatUsage);
            for (int i = 0; i < optBoatUsage.loops; i++) {
                for (int j = 0; j < optBoatUsage.swapBoats; j++) {
                    Schedule mutation = schedules.get(random.nextInt(schedules.size()));
                    mutation = MutationUtil.swapBoats(mutation, random);
                    if (!schedules.contains(mutation)) {
                        schedules.add(mutation);
                    }
                }
                for (int j = 0; j < optBoatUsage.swapRaces; j++) {
                    Schedule mutation = schedules.get(random.nextInt(schedules.size()));
                    mutation = MutationUtil.swapRaces(mutation, random);
                    if (!schedules.contains(mutation)) {
                        schedules.add(mutation);
                    }
                }
                schedules.sort(Comparator.comparingDouble(scorer::scoreWithCache));
                if (schedules.size() > optBoatUsage.individuals) {
                   /* for (Schedule schedule : schedules.subList(individuals, schedules.size())) {
                        hashes.remove(Integer.valueOf(schedule.hashCode()));
                    }*/
                    schedules = schedules.subList(0, optBoatUsage.individuals);
                }
                if (i == optBoatUsage.loops - 1 || (optBoatUsage.saveEveryN > 0 && counter % optBoatUsage.saveEveryN == 0)) {
                    System.out.println("------------  " + i + "  -----------------------");
                    //System.out.println("best1:" + scorer1.score(schedules.get(0)));
                    printQuality("best", scorer, schedules.get(0));
//                    printQuality("middle", scorer, schedules.get(schedules.size() / 2));
                    printQuality("worst", scorer, schedules.get(schedules.size() - 1));
                    //System.out.println(saveFuel.score(properties, schedules.get(0)));
                    //Util.printMatchMatrix(properties, schedules.get(0));
                    BoatMatrix matchMatrix = new BoatMatrix(properties);
                    for (int flightIdx = 0; flightIdx < schedule.size(); flightIdx++) {
                        Flight flight = schedule.get(flightIdx);
                        matchMatrix.add(flight);
                    }
                    int[] boatDistribution = matchMatrix.getBoatDistribution();
                    Util.printCount(boatDistribution, false);
                    int[] ii = getInterFlightStat(schedules.get(0));
                    System.out.println(String.format("saved Shuttles: in habour: %d at sea: %d - boat changes: %d", ii[0], ii[1], ii[2]));
                }
                for (Schedule s : schedules) {
                    s.getOlder();
                }
                counter++;
                if (optBoatUsage.earlyStopping > 0 && schedules.get(0).getAge() >= optBoatUsage.earlyStopping) {
                    System.out.println("Early Stopping applied");
                    break;
                }
                if (saver != null && optBoatUsage.saveEveryN > 0 && counter % optBoatUsage.saveEveryN == 0) {
                    saver.accept(schedules.get(0));
                }
            }
        }
        return schedules.get(0);

    }
git
    public static void main(String[] args) throws Exception {
        Options options = new Options();

        Option scheduleConfig = new Option(
                "s",
                "schedule_config",
                true,
                "the path to the yaml-file containing the schedule configuration");
        scheduleConfig.setRequired(false);
        options.addOption(scheduleConfig);

        Option optimizationConfig = new Option(
                "oc",
                "opt",
                true,
                "the path to the yaml-file containing the schedule configuration. If not given, take default configuration");
        optimizationConfig.setRequired(false);
        options.addOption(optimizationConfig);

        Option displayConfig = new Option(
                "dc",
                "display",
                true,
                "the path to the yaml-file containing the display configuration for the pdf");
        displayConfig.setRequired(false);
        options.addOption(displayConfig);

        Option outPdf = new Option(
                "plp",
                "pairing_list_pdf",
                true,
                "if given, save the pdf to the given path");
        displayConfig.setRequired(false);
        options.addOption(outPdf);


        Option input = new Option(
                "pli",
                "pairing_list_in",
                true,
                "if given, start with this configuration (must fit to schedule configuration), otherwise use random.");
        input.setRequired(false);
        options.addOption(input);

        Option outYml = new Option(
                "plo",
                "pairing_list_out",
                true,
                "if given, save best schedule to this file as yaml-structure");
        outYml.setRequired(false);
        options.addOption(outYml);
        Option outCsv = new Option(
                "plc",
                "pairing_list_csv",
                true,
                "if given, save best schedule to this file as csv-structure");
        outCsv.setRequired(false);
        options.addOption(outCsv);

        CommandLineParser parser = new DefaultParser();
        HelpFormatter formatter = new HelpFormatter();
        CommandLine cmd = null;

        try {
            cmd = parser.parse(options, args);
        } catch (ParseException e) {
            System.out.println(e.getMessage());
            formatter.printHelp("Method to calculate a Pairing List for the Liga-Format", options);
            System.exit(1);
        }

        String scheduleConfigValue = cmd.getOptionValue(scheduleConfig, "schedule_cfg.yml");
        ScheduleConfig scheduleProps = ScheduleConfig.readYaml(scheduleConfigValue);

        String optimizationConfigValue = cmd.getOptionValue(optimizationConfig, "opt_cfg.yml");
        OptimizationConfig optimizationProps = OptimizationConfig.readYaml(optimizationConfigValue);

        String displayConfigValue = cmd.getOptionValue(displayConfig, "display_cfg.yml");
        String outputValue = cmd.getOptionValue(outYml, "pairing_list.yml");
        String outPdfValue = cmd.getOptionValue(outPdf, "pairing_list.pdf");
        String outCsvValue = cmd.getOptionValue(outPdf, "pairing_list.csv");
        String inputValue = cmd.getOptionValue(input, null);


        DisplayConfig displayProps = DisplayConfig.readYaml(displayConfigValue);
        Random random = new Random(optimizationProps.seed);
        class Saver implements Consumer<Schedule> {

            @Override
            @SneakyThrows
            public void accept(Schedule schedule) {
                if (outputValue != null) {
                    schedule.writeYaml(new File(outputValue));
                }
                if (outCsvValue != null) {
                    schedule.writeCSV(new File(outCsvValue));
                }
                if (outPdfValue != null) {
                    new PdfCreator(displayProps, scheduleProps, new File(outPdfValue))
                            .create(schedule, new Random(optimizationProps.seed));
                }

            }
        }
        Saver saver = new Saver();

        Schedule schedule = inputValue == null ?
                Util.getRandomSchedule(scheduleProps, random) :
                Schedule.readYaml(new File(inputValue));

        Optimizer optimizer = new Optimizer();
        optimizer.init(scheduleProps, optimizationProps, random);
        schedule = optimizer.optimizeMatchMatrix(saver);
        if (optimizationProps.optMatchMatrix.loops > 0) {
            schedule = Util.shuffleBoats(schedule, random);
        }
        schedule = optimizer.optimizeBoatSchedule(schedule, saver);

        saver.accept(schedule);
    }
}
