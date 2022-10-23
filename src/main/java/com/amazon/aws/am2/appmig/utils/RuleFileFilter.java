package com.amazon.aws.am2.appmig.utils;

import java.io.File;
import java.io.FilenameFilter;
import static com.amazon.aws.am2.appmig.constants.IConstants.FILE_RECOMMENDATIONS;
import static com.amazon.aws.am2.appmig.constants.IConstants.RULES;
import static com.amazon.aws.am2.appmig.constants.IConstants.RECOMMENDATION;

public class RuleFileFilter implements FilenameFilter {

	private String[] ruleFiles;
	private String type;

	public RuleFileFilter() {
	}

	public RuleFileFilter(String[] ruleFiles, String type) {
		this.ruleFiles = ruleFiles;
		this.type = type;
	}

	@Override
	public boolean accept(File dir, String name) {
		for (String ruleFile : ruleFiles) {
			if (name.startsWith(ruleFile) && ((!name.endsWith(FILE_RECOMMENDATIONS) && type.equalsIgnoreCase(RULES))
					|| (name.endsWith(FILE_RECOMMENDATIONS) && type.equalsIgnoreCase(RECOMMENDATION)))) {
				return true;
			}
		}
		return false;
	}

}
