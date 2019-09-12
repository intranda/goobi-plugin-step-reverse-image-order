package de.intranda.goobi.plugins.step.reverseImageOrder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.configuration.SubnodeConfiguration;
import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.configuration.reloading.FileChangedReloadingStrategy;
import org.apache.commons.configuration.tree.xpath.XPathExpressionEngine;
import org.goobi.beans.LogEntry;
import org.goobi.beans.Step;
import org.goobi.production.enums.LogType;
import org.goobi.production.enums.PluginGuiType;
import org.goobi.production.enums.PluginReturnValue;
import org.goobi.production.enums.PluginType;
import org.goobi.production.enums.StepReturnValue;
import org.goobi.production.plugin.interfaces.IStepPluginVersion2;

import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.SwapException;
import de.sub.goobi.persistence.managers.ProcessManager;
import lombok.extern.log4j.Log4j;
import net.xeoh.plugins.base.annotations.PluginImplementation;
import ugh.dl.DigitalDocument;
import ugh.dl.DocStruct;
import ugh.dl.Metadata;
import ugh.dl.MetadataType;
import ugh.dl.Prefs;
import ugh.exceptions.PreferencesException;
import ugh.exceptions.ReadException;
import ugh.exceptions.WriteException;

@Log4j
@PluginImplementation
public class ReverseImageOrderPlugin implements IStepPluginVersion2 {
    private static String title = "intranda_step_reverseImageOrder";

    private Step step;
    private String returnPath;

    @Override
    public String cancel() {
        return null;
    }

    @Override
    public boolean execute() {
        return this.run() == PluginReturnValue.FINISH;
    }

    @Override
    public String finish() {
        return null;
    }

    @Override
    public String getPagePath() {
        return null;
    }

    @Override
    public PluginGuiType getPluginGuiType() {
        return PluginGuiType.NONE;
    }

    @Override
    public Step getStep() {
        return this.step;
    }

    @Override
    public void initialize(Step step, String returnPath) {
        this.step = step;
        this.returnPath = returnPath;
    }

    @Override
    public HashMap<String, StepReturnValue> validate() {
        return null;
    }

    @Override
    public String getTitle() {
        return title;
    }

    @Override
    public PluginType getType() {
        return PluginType.Step;
    }

    @Override
    public int getInterfaceVersion() {
        return 0;
    }

    @Override
    public PluginReturnValue run() {
        SubnodeConfiguration conf = getConfig();
        List<String> confLanguages = Arrays.asList(conf.getStringArray("language"));
        Set<String> reverseLanguages = new TreeSet<>();
        reverseLanguages.addAll(confLanguages);
        boolean reverseImages = false;
        try {
            Prefs prefs = this.step.getProzess().getRegelsatz().getPreferences();
            MetadataType langType = prefs.getMetadataTypeByName("DocLanguage");
            DigitalDocument dd = this.step.getProzess().readMetadataFile().getDigitalDocument();
            DocStruct ds = dd.getLogicalDocStruct();
            if (ds == null) {
                LogEntry le = LogEntry.build(this.step.getProcessId())
                        .withType(LogType.ERROR)
                        .withContent(title + ": Logical Docstruct is null")
                        .withCreationDate(new Date());
                ProcessManager.saveLogEntry(le);
                log.error(title + ": " + step.getProzess().getTitel() + ": Logical docstruct is null");
                return PluginReturnValue.ERROR;
            }
            @SuppressWarnings("unchecked")
            List<Metadata> languages = (List<Metadata>) ds.getAllMetadataByType(langType);
            for (Metadata meta : languages) {
                reverseImages |= reverseLanguages.contains(meta.getValue());
            }

        } catch (PreferencesException | ReadException | WriteException | IOException | InterruptedException | SwapException | DAOException e) {
            LogEntry le = LogEntry.build(this.step.getProcessId())
                    .withType(LogType.ERROR)
                    .withContent(title + ": Error reading metadata")
                    .withCreationDate(new Date());
            ProcessManager.saveLogEntry(le);
            log.error(e);
            return PluginReturnValue.ERROR;
        }
        if (reverseImages) {
            try {
                this.reverseImagesAndOcr();
            } catch (IOException | InterruptedException | SwapException | DAOException e) {
                LogEntry le = LogEntry.build(this.step.getProcessId())
                        .withType(LogType.ERROR)
                        .withContent(title + ": Error reversing images")
                        .withCreationDate(new Date());
                ProcessManager.saveLogEntry(le);
                log.error(e);
                return PluginReturnValue.ERROR;
            }
        }
        return PluginReturnValue.FINISH;
    }

    public SubnodeConfiguration getConfig() {
        String projectName = step.getProzess().getProjekt().getTitel();
        XMLConfiguration xmlConfig = ConfigPlugins.getPluginConfig(title);
        xmlConfig.setExpressionEngine(new XPathExpressionEngine());
        xmlConfig.setReloadingStrategy(new FileChangedReloadingStrategy());

        SubnodeConfiguration conf = null;

        // order of configuration is:
        // 1.) project name and step name matches
        // 2.) step name matches and project is *
        // 3.) project name matches and step name is *
        // 4.) project name and step name are *
        try {
            conf = xmlConfig.configurationAt("//config[./project = '" + projectName + "'][./step = '" + step.getTitel() + "']");
        } catch (IllegalArgumentException e) {
            try {
                conf = xmlConfig.configurationAt("//config[./project = '*'][./step = '" + step.getTitel() + "']");
            } catch (IllegalArgumentException e1) {
                try {
                    conf = xmlConfig.configurationAt("//config[./project = '" + projectName + "'][./step = '*']");
                } catch (IllegalArgumentException e2) {
                    conf = xmlConfig.configurationAt("//config[./project = '*'][./step = '*']");
                }
            }
        }
        return conf;
    }

    private void reverseImagesAndOcr() throws IOException, InterruptedException, SwapException, DAOException {
        org.goobi.beans.Process p = step.getProzess();
        reverseContents(Paths.get(p.getImagesOrigDirectory(false)));
        reverseContents(Paths.get(p.getImagesTifDirectory(false)));
        reverseContents(Paths.get(p.getImagesDirectory()).resolve(p.getTitel() + "_jpg"));
        reverseContents(Paths.get(p.getOcrAltoDirectory()));
        reverseContents(Paths.get(p.getOcrTxtDirectory()));
        reverseContents(Paths.get(p.getOcrXmlDirectory()));
        reverseContents(Paths.get(p.getOcrWcDirectory()));
        reverseContents(Paths.get(p.getOcrPdfDirectory()));
    }

    private void reverseContents(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        List<Path> files = listDir(dir);
        List<String> tmpNames = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            Path file = files.get(i);
            String oldName = file.getFileName().toString();
            String ext = oldName.substring(oldName.lastIndexOf('.'));
            String tmpName = String.format("tmp_%08d%s", files.size() - i, ext);
            tmpNames.add(tmpName);
            Files.move(file, dir.resolve(tmpName));
        }
        for (String tmpName : tmpNames) {
            Files.move(dir.resolve(tmpName), dir.resolve(tmpName.substring(4)));
        }
    }

    private List<Path> listDir(Path dir) throws IOException {
        try (Stream<Path> dirStream = Files.list(dir)) {
            return dirStream.sorted().collect(Collectors.toList());
        }
    }

}
