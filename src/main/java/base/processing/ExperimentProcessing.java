package base.processing;

import SerialDriver.SerialDriver;
import base.Editor;
import base.PreferencesData;
import base.helpers.CheckModules;
import base.serial.SerialDiscovery;
import libraries.I7000;

import java.lang.reflect.Array;
import java.util.*;
import java.util.stream.IntStream;

import static base.helpers.BaseHelper.parsePortException;

public class ExperimentProcessing implements Runnable {

    private final Editor editor;
    private final Experiment experiment;
    private SerialDriver serialDriver;
    private StringBuffer serialBuffer;
    private volatile boolean stopExperiment;
    private boolean useFirstBuffer = true;
    private List<String> bufferOne = Collections.synchronizedList(new ArrayList<>());
    private List<String> bufferTwo = Collections.synchronizedList(new ArrayList<>());
    private Timer checkErrorStatusTimer;
    private volatile Integer[] err;

    public ExperimentProcessing(Editor editor, Experiment experiment) {
        this.editor = editor;
        this.experiment = experiment;
    }

    public void stop() {
        stopExperiment = true;
        checkErrorStatusTimer.cancel();
    }

    private void stopAll() {
        editor.handleStop();
    }

    public void start() throws Exception {
        StringBuilder result = new StringBuilder();
        float[] signal = experiment.generateSignal();
        String[] commands = generateDacCommands(signal);
        String[] otherCommands = generateOtherCommands();
        String synchronizedSampling = I7000.getSynchronizedSampling();
        int responseTimeout = PreferencesData.getInteger("response.timeout");
        int period = responseTimeout / (otherCommands.length + 1) - 5;
        long timeAll, time;
        boolean flag;
        int sErr;
        while (!stopExperiment) {
            for (int i = 0; i < commands.length; i++) {
                timeAll = System.currentTimeMillis();
                serialBuffer.setLength(0);
                result.setLength(0);
                time = System.currentTimeMillis();
                serialDriver.write(commands[i]);
                sErr = 0;
                flag = false;
                while ((System.currentTimeMillis() - time < period)) {
                    if (serialBuffer.indexOf("\r") != -1) {
                        flag = true;
                        if (serialBuffer.indexOf(">") == -1) {
                            err[sErr]++;
                            break;
                        }
                        err[sErr] = 0;
                        break;
                    }
                    Thread.sleep(1);
                }
                result.append(signal[i]);
                if (!flag) err[sErr]++;
                sErr++;
                if (stopExperiment) return;
                serialDriver.write(synchronizedSampling);
                for (String command : otherCommands) {
                    result.append(",");
                    serialBuffer.setLength(0);
                    serialDriver.write(command);
                    time = System.currentTimeMillis();
                    flag = false;
                    while ((System.currentTimeMillis() - time < period)) {
                        if (serialBuffer.indexOf("\r") != -1) {
                            flag = true;
                            if (serialBuffer.indexOf(">") == -1) {
                                err[sErr]++;
                                result.append("NULL");
                                break;
                            }
                            result.append(I7000.removeCRC(1, serialBuffer));
                            err[sErr] = 0;
                            break;
                        }
                        Thread.sleep(1);
                    }
                    if (!flag) {
                        err[sErr]++;
                        result.append("NULL");
                    }
                    sErr++;
                }
                if (useFirstBuffer) {
                    bufferOne.add(String.valueOf(result));
                } else {
                    bufferTwo.add(String.valueOf(result));
                }
                while ((System.currentTimeMillis() - timeAll < responseTimeout)) {
                    Thread.sleep(1);
                }
                System.out.println(System.currentTimeMillis() - timeAll);
                if (stopExperiment) return;
            }
        }

    }

    private String[] generateOtherCommands() {
        String[] IdModules = (PreferencesData.getCollection("runtime.Id.modules")).toArray(new String[0]);
        String[] str = new String[IdModules.length];
        for (int i = 0; i < IdModules.length; i++) {
            str[i] = I7000.setAnalogInTechnicalUnits(IdModules[i]);
        }
        return str;
    }

    private String[] generateDacCommands(float[] signal) {
        String dacId = PreferencesData.get("runtime.dac.module");
        String[] str = new String[signal.length];
        for (int i = 0; i < signal.length; i++) {
            str[i] = I7000.setAnalogOutTechnicalUnits(dacId, signal[i]);
        }
        return str;
    }

    @Override
    public void run() {
        String port = PreferencesData.get("serial.port");
        String rate = PreferencesData.get("serial.port.rate");
        serialBuffer = new StringBuffer();
        try {
            serialDriver = new SerialDriver(port, rate, this::dataReadAction);
        } catch (Exception e) {
            parsePortException(editor, e);
            editor.handleStop();
            return;
        }
        stopExperiment = false;
        bufferOne.clear();
        bufferTwo.clear();
        checkErrorStatus();
        try {
            start();
        } catch (Exception e) {
            e.printStackTrace();
        }
        serialDriver.dispose();
    }

    private void checkErrorStatus() {
        err = new Integer[PreferencesData.getInteger("number.of.modules")];
        Arrays.fill(err, 0);
        int period = PreferencesData.getInteger("response.timeout", 200) * 3;
        checkErrorStatusTimer = new Timer(ExperimentProcessing.class.getName());
        checkErrorStatusTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                if (IntStream.range(0, err.length).filter(i -> err[i] > 2).findFirst().orElse(-1) != -1) {
                    editor.statusError("Помилка очікування");
                    editor.statusError("Модуль не відповідає");
                    for (String s : bufferOne)
                    System.out.println(s);
                    stopAll();
                }
            }
        }, 0, period);
    }

    private void dataReadAction(String s) {
        serialBuffer.append(s);
    }
}
