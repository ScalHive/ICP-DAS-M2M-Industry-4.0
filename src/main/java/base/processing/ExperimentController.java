package base.processing;

import base.Editor;

public class ExperimentController {

    private final Editor editor;
    private final Experiment experiment;

    public ExperimentController(Editor editor, Experiment experiment) {
        this.editor = editor;
        this.experiment = experiment;
        initExperiment();
    }

    public void exit() {
        if (experiment.isUntitledAndNotSaved()) {
            base.helpers.FileUtils.recursiveDelete(experiment.getFolder());
        }
    }

    private void initExperiment() {
        if (experiment.isUntitledAndNotSaved()) return;
        // some code
    }

}
