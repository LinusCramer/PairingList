package gundramleifert.pairing_list;

import gundramleifert.pairing_list.configs.DisplayProps;
import gundramleifert.pairing_list.configs.OptimizationProps;
import gundramleifert.pairing_list.configs.ScheduleProps;
import gundramleifert.pairing_list.cost_calculators.CostCalculatorBoatSchedule;
import gundramleifert.pairing_list.cost_calculators.CostCalculatorMatchMatrix;
import gundramleifert.pairing_list.cost_calculators.ICostCalculator;
import gundramleifert.pairing_list.types.BoatMatrix;
import gundramleifert.pairing_list.types.Flight;
import gundramleifert.pairing_list.types.Schedule;
import lombok.SneakyThrows;
import org.apache.commons.cli.*;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;

import static gundramleifert.pairing_list.cost_calculators.CostCalculatorBoatSchedule.getInterFlightStat;

public class Optimizer {
    private ScheduleProps properties;
    private OptimizationProps optProps;
    private Random r;

    public void init(ScheduleProps properties, OptimizationProps optimizationProps) throws Exception {
        this.properties = properties;
        this.optProps = optimizationProps;
        r = new Random(optProps.seed);

    }

    private static void printQuality(String prefix, ICostCalculator scorer, Schedule schedule) {
        System.out.format("Score:%6.2f Age:%3d %s\n", scorer.score(schedule), schedule.getAge(), prefix);
    }

    public Schedule optimizeMatchMatrix(Schedule schedule, Consumer<Schedule> saver) throws Exception {
        List<Schedule> schedules = new ArrayList<>();
        schedules.add(schedule);
        final CostCalculatorMatchMatrix scorer = new CostCalculatorMatchMatrix(properties);
        int counter = 0;
        for (OptimizationProps.OptMatchMatrix optMatchMatrix : optProps.optMatchMatrix) {
            for (int i = 0; i < optMatchMatrix.loops; i++) {
                for (int j = 0; j < optMatchMatrix.swapTeams; j++) {
                    Schedule mutation = schedules.get(r.nextInt(schedules.size()));
                    mutation = MutationUtil.swapBetweenRaces(mutation, r);
                    if (!schedules.contains(mutation)) {
                        schedules.add(mutation);
                    }
                }
                for (int j = 0; j < optMatchMatrix.merges; j++) {
                    int idxFather = r.nextInt(schedules.size());
                    int idxMother = (idxFather + 1 + r.nextInt(schedules.size() - 1)) % schedules.size();
                    Schedule father = schedules.get(idxFather);
                    Schedule mother = schedules.get(idxMother);
                    Schedule mutation = MutationUtil.mutation(father, mother, r);
                    if (!schedules.contains(mutation)) {
                        schedules.add(mutation);
                    }
                }
                schedules.sort(Comparator.comparingDouble(scorer::scoreWithCache));
                if (schedules.size() > optMatchMatrix.individuals) {
                   /* for (Schedule schedule : schedules.subList(individuals, schedules.size())) {
                        hashes.remove(Integer.valueOf(schedule.hashCode()));
                    }*/
                    schedules = schedules.subList(0, optMatchMatrix.individuals);
                }
                if (i == optMatchMatrix.loops - 1 || i % (optMatchMatrix.loops / 10) == 0) {
                    System.out.println("------------  " + i + "  -----------------------");
                    //System.out.println("best1:" + scorer1.score(schedules.get(0)));
                    printQuality("best", scorer, schedules.get(0));
                    printQuality("middle", scorer, schedules.get(schedules.size() / 2));
                    printQuality("worst", scorer, schedules.get(schedules.size() - 1));
                    //System.out.println(saveFuel.score(properties, schedules.get(0)));
                    //Util.printMatchMatrix(properties, schedules.get(0));
                    Util.printCount(properties, schedules.get(0));
                }
                for (Schedule s : schedules) {
                    s.getOlder();
                }
                counter++;
                if (optMatchMatrix.earlyStopping > 0 && schedules.get(0).getAge() >= optMatchMatrix.earlyStopping) {
                    System.out.println("Early Stopping applied");
                    break;
                }
                if (saver != null && optMatchMatrix.saveEveryN > 0 && counter % optMatchMatrix.saveEveryN == 0) {
                    saver.accept(schedules.get(0));
                }

            }
        }
        return schedules.get(0);

    }

    public Schedule optimizeBoatSchedule(Schedule schedule, Consumer<Schedule> saver) {
        List<Schedule> schedules = new ArrayList<>();
        schedules.add(schedule);
        int counter = 0;
        for (OptimizationProps.OptBoatUsage optBoatUsage : optProps.optBoatUsage) {
            final CostCalculatorBoatSchedule scorer = new CostCalculatorBoatSchedule(properties, optBoatUsage);
            for (int i = 0; i < optBoatUsage.loops; i++) {
                for (int j = 0; j < optBoatUsage.swapBoats; j++) {
                    Schedule mutation = schedules.get(r.nextInt(schedules.size()));
                    mutation = MutationUtil.swapBoats(mutation, r);
                    if (!schedules.contains(mutation)) {
                        schedules.add(mutation);
                    }
                }
                for (int j = 0; j < optBoatUsage.swapRaces; j++) {
                    Schedule mutation = schedules.get(r.nextInt(schedules.size()));
                    mutation = MutationUtil.swapRaces(mutation, r);
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
                if (i == optBoatUsage.loops - 1 || i % (optBoatUsage.loops / 10) == 0) {
                    System.out.println("------------  " + i + "  -----------------------");
                    //System.out.println("best1:" + scorer1.score(schedules.get(0)));
                    printQuality("best", scorer, schedules.get(0));
                    printQuality("middle", scorer, schedules.get(schedules.size() / 2));
                    printQuality("worst", scorer, schedules.get(schedules.size() - 1));
                    //System.out.println(saveFuel.score(properties, schedules.get(0)));
                    //Util.printMatchMatrix(properties, schedules.get(0));
                    BoatMatrix matchMatrix = new BoatMatrix(properties);
                    int[][] values = new int[properties.flights][];
                    for (int flightIdx = 0; flightIdx < schedule.flights.length; flightIdx++) {
                        Flight flight = schedule.flights[flightIdx];
                        matchMatrix.add(flight);
                    }
                    int[] boatDistribution = matchMatrix.getBoatDistribution();
                    Util.printCount(boatDistribution,false);
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

    public static void main(String[] args) throws Exception {
        Options options = new Options();

        Option scheduleConfig = new Option(
                "s",
                "schedule_config",
                true,
                "the path to the yaml-file containing the schedule configuration");
        scheduleConfig.setRequired(true);
        options.addOption(scheduleConfig);

        Option optimizationConfig = new Option(
                "opt",
                true,
                "the path to the yaml-file containing the schedule configuration. If not given, take default configuration");
        optimizationConfig.setRequired(false);
        options.addOption(optimizationConfig);

        Option displayConfig = new Option(
                "d",
                "display_config",
                true,
                "the path to the yaml-file containing the display configuration for the pdf");
        displayConfig.setRequired(false);
        options.addOption(displayConfig);

        Option outPdf = new Option(
                "p",
                "out_pdf",
                true,
                "if given, save the pdf to the given path");
        displayConfig.setRequired(false);
        options.addOption(outPdf);


        Option input = new Option(
                "i",
                "in",
                true,
                "if given, start with this configuration (must fit to schedule configuration), otherwise use random.");
        input.setRequired(false);
        options.addOption(input);

        Option output = new Option(
                "o",
                "out_schedule",
                true,
                "if given, save best schedule to this file as yaml-structure");
        output.setRequired(false);
        options.addOption(output);

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

        String scheduleConfigValue = cmd.getOptionValue(scheduleConfig, null);
        if (scheduleConfigValue == null) {
            throw new ParseException("no schedule configuration given " + scheduleConfig);
        }
        ScheduleProps scheduleProps = ScheduleProps.readYaml(scheduleConfigValue);

        String optimizationConfigValue = cmd.getOptionValue(optimizationConfig, "optimization_properties_default.yml");
        OptimizationProps optimizationProps = OptimizationProps.readYaml(optimizationConfigValue);

        String displayConfigValue = cmd.getOptionValue(displayConfig, "display_properties_default.yml");
        DisplayProps displayProps = DisplayProps.readYaml(displayConfigValue);
        String outputValue = cmd.getOptionValue(output);
        String outPdfValue = cmd.getOptionValue(outPdf);
        class Saver implements Consumer<Schedule> {

            @Override
            @SneakyThrows
            public void accept(Schedule schedule) {
                if (outputValue != null) {
                    schedule.writeYaml(new File(outputValue));
                }
                if (outPdfValue != null) {
                    new PdfCreator(displayProps, scheduleProps, new File(outPdfValue)).create(schedule, new Random(optimizationProps.seed));
                }

            }
        }
        Saver saver = new Saver();

        String inputValue = cmd.getOptionValue(input);
        Schedule schedule = inputValue == null ?
                Util.getRandomSchedule(scheduleProps, new Random(optimizationProps.seed)) :
                Schedule.readYaml(new File(inputValue));

        Optimizer optimizer = new Optimizer();
        optimizer.init(scheduleProps, optimizationProps);
        schedule = optimizer.optimizeMatchMatrix(schedule,saver);
        if (optimizationProps.optMatchMatrix.size() > 1 && optimizationProps.optMatchMatrix.get(0).loops > 0) {
            schedule = Util.shuffleBoats(schedule, new Random(optimizationProps.seed));
        }
        schedule = optimizer.optimizeBoatSchedule(schedule,saver);

        saver.accept(schedule);
    }
}
