package com.amazon.aws.am2.appmig.estimate.xml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import com.amazon.aws.am2.appmig.search.ISearch;
import com.amazon.aws.am2.appmig.search.RegexSearch;
import org.apache.commons.lang3.StringUtils;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.amazon.aws.am2.appmig.estimate.CodeMetaData;
import com.amazon.aws.am2.appmig.estimate.IAnalyzer;
import com.amazon.aws.am2.appmig.estimate.Plan;
import com.amazon.aws.am2.appmig.estimate.exception.InvalidRuleException;
import com.amazon.aws.am2.appmig.estimate.exception.NoRulesFoundException;
import com.amazon.aws.am2.appmig.report.ReportSingletonFactory;
import com.amazon.aws.am2.appmig.utils.Utility;

import static com.amazon.aws.am2.appmig.constants.IConstants.*;

public class XMLFileAnalyzer implements IAnalyzer {

    private final static Logger LOGGER = LoggerFactory.getLogger(XMLFileAnalyzer.class);
    private String path;
    private int loc;
    private String ruleFileName;
    private String basePackage;
    private String fileType;
    private String projectId;
    private String src;
    private JSONArray rules;
    private List<String> xmlLines;
    private Element element;
    public final String TAG_NAME = "tagName";
    public final String TAG_CONTENT = "tagContent";
    public final String ATTRIBUTE_NAME = "attributeName";
    public final String ATTRIBUTE_VALUE = "attributeValue";

    @Override
    public boolean analyze(String path) throws NoRulesFoundException {
        LOGGER.debug("Analyzing file {}", path);
        if (rules == null || rules.size() == 0) {
            throw new NoRulesFoundException("Rules needs to be set before calling analyzer!");
        }
        boolean taskCompleted = true;
        this.path = path;
        Path filePath = Paths.get(path);
        try {
            xmlLines = Files.readAllLines(filePath);
            DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
            dbFactory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbFactory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            dbFactory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            dbFactory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            dbFactory.setXIncludeAware(false);
            dbFactory.setExpandEntityReferences(false);
            DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
            Document doc = dBuilder.parse(Files.newInputStream(filePath));
            element = doc.getDocumentElement();
            doc.getDocumentElement().normalize();
            for (Object obj : rules) {
                try {
                    JSONObject rule = (JSONObject) obj;
                    applyRule(rule);
                } catch (InvalidRuleException e) {
                    taskCompleted = false;
                    LOGGER.error("Unable to apply rule on path {} due to {}", path, Utility.parse(e));
                }
            }
        } catch (IOException ioe) {
            taskCompleted = false;
            LOGGER.error("File not found! {} Got exception! {}", path, Utility.parse(ioe));
        } catch (Exception exp) {
            taskCompleted = false;
            LOGGER.error("Unable to parse the file {} due to {}", path, Utility.parse(exp));
        }
        return taskCompleted;
    }

    public void applyRule(JSONObject rule) throws InvalidRuleException {
        List<CodeMetaData> lstCodeMetaData = null;
        Plan plan = Utility.convertRuleToPlan(rule);
        if (rule.get(REMOVE) != null && rule.get(ADD) == null) {
            JSONObject removeRule = (JSONObject) rule.get(REMOVE);
            lstCodeMetaData = processRule(removeRule);
        } else if (rule.get(SEARCH) != null) {
            JSONObject searchRule = (JSONObject) rule.get(SEARCH);
            lstCodeMetaData = processSearchRule(searchRule);
        }
        if (lstCodeMetaData != null && lstCodeMetaData.size() > 0) {
            for (CodeMetaData cd : lstCodeMetaData) {
                plan.addDeletion(cd);
            }
            ReportSingletonFactory.getInstance().getStandardReport().addOnlyDeletions(path, plan);
        }
    }

    private List<CodeMetaData> processSearchRule(JSONObject rule) throws InvalidRuleException {
        List<CodeMetaData> lstCodeMetaData = new ArrayList<>();
        @SuppressWarnings("unchecked")
        Set<String> keys = rule.keySet();
        if (keys.contains(PATTERN)) {
            Object patternObj = rule.get(PATTERN);
            if (patternObj == null) {
                throw new InvalidRuleException("pattern is not defined for " + rule);
            }
            String pattern = patternObj.toString();
            ISearch search = new RegexSearch();
            int lineCnt = -1;
            for (String xmlLine : this.xmlLines) {
                lineCnt++;
                if (search.find(pattern, xmlLine, true)) {
                    CodeMetaData metaData = new CodeMetaData(lineCnt + 1, xmlLines.get(lineCnt), IAnalyzer.SUPPORTED_LANGUAGES.LANG_MARKUP.getLanguage());
                    lstCodeMetaData.add(metaData);
                }
            }
        }
        return lstCodeMetaData;
    }

    private List<CodeMetaData> processRule(JSONObject rule) {
        List<CodeMetaData> lstCodeMetaData = new ArrayList<>();
        @SuppressWarnings("unchecked")
        Set<String> keys = rule.keySet();
        if (keys.contains(TAG_NAME)) {
            NodeList values = element.getElementsByTagName((String) rule.get(TAG_NAME));
            if (keys.contains(TAG_NAME) && !keys.contains(TAG_CONTENT) && !keys.contains(ATTRIBUTE_NAME) &&
                    (element.getTagName().equals(rule.get(TAG_NAME)) || (values.getLength() > 0))) {
                lstCodeMetaData.add(fetchLine((String) rule.get(TAG_NAME)));
            } else {
                int nodeListLen = values.getLength();
                for (int i = 0; i < nodeListLen; i++) {
                    String actualContext = values.item(i).getTextContent();
                    if (keys.contains(TAG_CONTENT)) {
                        String expectedValue = (String) rule.get(TAG_CONTENT);
                        if (!StringUtils.equalsIgnoreCase(expectedValue, "*")
                                && StringUtils.equalsIgnoreCase(actualContext, expectedValue)) {
                            lstCodeMetaData.add(fetchLine(actualContext));
                        }
                    } else if (keys.contains(ATTRIBUTE_NAME) && keys.contains(ATTRIBUTE_VALUE)) {
                        String expectedAttributeName = (String) rule.get(ATTRIBUTE_NAME);
                        String expectedAttributeValue = (String) rule.get(ATTRIBUTE_VALUE);
                        NamedNodeMap attributeMap = values.item(i).getAttributes();
                        Node attributeNode = attributeMap.getNamedItem(expectedAttributeName);
                        if (attributeNode != null && attributeNode.getNodeValue().equalsIgnoreCase(expectedAttributeValue)) {
                            lstCodeMetaData.add(fetchLine(attributeNode.getTextContent()));
                        }
                    }
                }
            }
        }
        return lstCodeMetaData;
    }

    private CodeMetaData fetchLine(String findValue) {
        int lineCnt = -1;
        for (String line : xmlLines) {
            lineCnt++;
            if (line.contains(findValue)) {
                break;
            }
        }
        return new CodeMetaData(lineCnt + 1, xmlLines.get(lineCnt), IAnalyzer.SUPPORTED_LANGUAGES.LANG_MARKUP.getLanguage());
    }

    @Override
    public void setRules(JSONArray rules) {
        this.rules = rules;
    }

    @Override
    public JSONArray getRules() {
        return rules;
    }

    @Override
    public String getRuleFileName() {
        return this.ruleFileName;
    }

    @Override
    public void setRuleFileName(String ruleFileName) {
        this.ruleFileName = ruleFileName;
    }

    @Override
    public String getFileType() {
        return this.fileType;
    }

    @Override
    public void setFileType(String fileType) {
        this.fileType = fileType;
    }

    @Override
    public void setSource(String src) {
        this.src = src;
    }

    @Override
    public String getSource() {
        return this.src;
    }

    @Override
    public void setBasePackage(String packageName) {
        this.basePackage = packageName;
    }

    @Override
    public String getBasePackage() {
        return this.basePackage;
    }

    @Override
    public void setProjectId(String id) {
        this.projectId = id;
    }

    @Override
    public String getProjectId() {
        return this.projectId;
    }

    @Override
    public int getLOC() {
        return this.loc;
    }

    @Override
    public void setLOC(int loc) {
       this.loc = loc;
    }
}
