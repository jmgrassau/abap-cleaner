package com.sap.adt.abapcleaner.rules.alignment;

import java.util.*;
import java.time.LocalDate;

import com.sap.adt.abapcleaner.base.ABAP;
import com.sap.adt.abapcleaner.parser.Code;
import com.sap.adt.abapcleaner.parser.Command;
import com.sap.adt.abapcleaner.parser.Token;
import com.sap.adt.abapcleaner.parser.TokenSearch;
import com.sap.adt.abapcleaner.programbase.UnexpectedSyntaxAfterChanges;
import com.sap.adt.abapcleaner.rulebase.ConfigEnumValue;
import com.sap.adt.abapcleaner.rulebase.ConfigIntValue;
import com.sap.adt.abapcleaner.rulebase.ConfigValue;
import com.sap.adt.abapcleaner.rulebase.Profile;
import com.sap.adt.abapcleaner.rulebase.RuleForCommands;
import com.sap.adt.abapcleaner.rulebase.RuleGroupID;
import com.sap.adt.abapcleaner.rulebase.RuleID;
import com.sap.adt.abapcleaner.rulebase.RuleReference;
import com.sap.adt.abapcleaner.rulebase.RuleSource;

public class AlignClearFreeAndSortRule extends RuleForCommands {
	private final static RuleReference[] references = new RuleReference[] { new RuleReference(RuleSource.ABAP_CLEANER) };

	@Override
	public RuleID getID() { return RuleID.ALIGN_CLEAR_FREE_AND_SORT; }

	@Override
	public RuleGroupID getGroupID() { return RuleGroupID.ALIGNMENT; }

	@Override
	public String getDisplayName() { return "Align CLEAR:, FREE: and SORT"; }

	@Override
	public String getDescription() {
		return "Aligns lists of variables after CLEAR: and FREE: and lists of components after SORT ... BY.";
	}

	@Override
	public LocalDate getDateCreated() { return LocalDate.of(2023, 5, 6); }

	@Override
	public RuleReference[] getReferences() { return references; }

	@Override
	public RuleID[] getDependentRules() { return new RuleID[] { RuleID.ALIGN_PARAMETERS }; }

   @Override
   public String getExample() {
      return "" 
			+ LINE_SEP + "  METHOD align_clear_free_and_sort." 
			+ LINE_SEP + "    CLEAR: mv_any_value," 
			+ LINE_SEP + "      mv_other_value," 
			+ LINE_SEP + "         ls_any_structure-any_component," 
			+ LINE_SEP + "        ls_any_structure-other_component," 
			+ LINE_SEP + "        mt_any_table, mt_other_table, mt_third_table." 
			+ LINE_SEP 
			+ LINE_SEP + "    CLEAR:   mv_any_value," 
			+ LINE_SEP + "      mv_other_value WITH lv_initial_value IN CHARACTER MODE," 
			+ LINE_SEP + "      mv_third_value WITH NULL," 
			+ LINE_SEP + "         mv_fourth_value WITH lv_initial_value IN BYTE MODE." 
			+ LINE_SEP 
			+ LINE_SEP + "    FREE: mt_any_table, mts_other_table," 
			+ LINE_SEP + "         mth_third_table." 
			+ LINE_SEP 
			+ LINE_SEP + "    SORT mt_any_table STABLE BY comp1 comp2" 
			+ LINE_SEP + "     comp3" 
			+ LINE_SEP + "     comp4." 
			+ LINE_SEP 
			+ LINE_SEP + "    SORT: mt_other_table BY   comp1 comp2 DESCENDING" 
			+ LINE_SEP + "     comp3 comp4 AS TEXT," 
			+ LINE_SEP + "          mt_third_table BY  component1 AS TEXT" 
			+ LINE_SEP + "      component2   component3." 
			+ LINE_SEP + "  ENDMETHOD.";
   }

	final ConfigIntValue configMaxLineLength = new ConfigIntValue(this, "MaxLineLength", "Maximum line length", "", 80, 120, ABAP.MAX_LINE_LENGTH);

	final ConfigEnumValue<DistinctLineClear> configDistinctLineClear = new ConfigEnumValue<DistinctLineClear>(this, "DistinctLineClear", "CLEAR: Use one line per variable:",
																								new String[] { "always", "only if additions are used", "never" }, DistinctLineClear.ALWAYS);
	final ConfigEnumValue<DistinctLineFree> configDistinctLineFree = new ConfigEnumValue<DistinctLineFree>(this, "DistinctLineFree", "FREE: Use one line per variable:",
																								new String[] { "always", "never" }, DistinctLineFree.ALWAYS);
	final ConfigEnumValue<DistinctLineSort> configDistinctLineSort = new ConfigEnumValue<DistinctLineSort>(this, "DistinctLineSort", "SORT: Use one line per variable:",
																								new String[] { "always", "only if additions are used", "never" }, DistinctLineSort.ALWAYS);

	private final ConfigValue[] configValues = new ConfigValue[] { configMaxLineLength, configDistinctLineClear, configDistinctLineFree, configDistinctLineSort };

	@Override
	public ConfigValue[] getConfigValues() { return configValues; }

	private DistinctLineClear getConfigDistinctLineClear() {
		return DistinctLineClear.forValue(configDistinctLineClear.getValue()); 
	}
	private DistinctLineFree getConfigDistinctLineFree() {
		return DistinctLineFree.forValue(configDistinctLineFree.getValue());
	}
	private DistinctLineSort getConfigDistinctLineSort() {
		return DistinctLineSort.forValue(configDistinctLineSort.getValue());
	}

	public AlignClearFreeAndSortRule(Profile profile) {
		super(profile);
		initializeConfiguration();
	}

	@Override
	protected boolean executeOn(Code code, Command command, int releaseRestriction) throws UnexpectedSyntaxAfterChanges {
		Token firstToken = command.getFirstCodeToken();
		if (firstToken == null)
			return false;
		
		// determine whether to create multiple lines and create a list of Tokens that start (or continue) these lines
		boolean distinctLine = false;
		ArrayList<Token> tokens = new ArrayList<>();

		if (command.firstCodeTokenIsAnyKeyword("CLEAR", "FREE")) {
			// CLEAR: dobj1 [ {WITH val [IN {CHARACTER|BYTE} MODE] } | {WITH NULL} ], djob2 ...
			// FREE: dobj1, dobj2 ...
			if (!command.isSimpleChain() || !firstToken.matchesOnSiblings(true, TokenSearch.ASTERISK, ","))
				return false;

			if (command.firstCodeTokenIsKeyword("CLEAR")) {
				DistinctLineClear distinctLineClear = getConfigDistinctLineClear();
				distinctLine = (distinctLineClear == DistinctLineClear.ALWAYS) || (distinctLineClear == DistinctLineClear.ONLY_WITH_ADDITIONS) && firstToken.matchesOnSiblings(true, TokenSearch.ASTERISK, "WITH"); 
			} else {
				distinctLine = (getConfigDistinctLineFree() == DistinctLineFree.ALWAYS); 
			}

			// create a list of the Tokens after the chain colon and after each comma
			Token token = firstToken.getNextCodeSibling();
			while (token != null) {
				boolean useNext = (token.isChainColon() || token.isComma());
				token = token.getNextCodeSibling();
				if (useNext && token != null)
					tokens.add(token);
			}

		} else if (command.firstCodeTokenIsKeyword("SORT")) {
			// SORT itab [STABLE] [ASCENDING|DESCENDING] [AS TEXT] BY { comp1 [ASCENDING|DESCENDING] [AS TEXT]} { comp2 ... }.
			
			// create a list of all identifiers after "BY"; only 'additions' after components (not after 'itab') count! 
			Token token = firstToken.getLastTokenOnSiblings(true, TokenSearch.ASTERISK, "BY");
			boolean additionFound = false;
			while (token != null) {
				if (token.textStartsWith("(") || token.isKeyword("VALUE")) {
					// skip cases of "SORT itab [STABLE] BY (otab)" and "SORT itab [STABLE] BY VALUE #( ... )"
				} else if (token.isIdentifier() && !token.getOpensLevel()) {
					tokens.add(token);
					if (!additionFound && token.matchesOnSiblings(true, TokenSearch.ANY_IDENTIFIER, "ASCENDING|DESCENDING|AS TEXT")) {
						additionFound = true;
					}
				}
				token = token.getNextCodeSibling();
				if (token != null && token.isComma()) {
					// move to the next "BY" keyword 
					token = token.getLastTokenOnSiblings(true, TokenSearch.ASTERISK, "BY");
				} 
			}
			DistinctLineSort distinctLineSort = getConfigDistinctLineSort();
			distinctLine = (distinctLineSort == DistinctLineSort.ALWAYS) || (distinctLineSort == DistinctLineSort.ONLY_WITH_ADDITIONS) && additionFound;
			
		} else {
			return false;
		}
		
		// anything to align?
		if (tokens.size() < 2)
			return false;

		boolean commandChanged = false;
		int maxLineLength = configMaxLineLength.getValue();
		Token firstInstance = tokens.get(0);
		int indent = 0; // will be determined below 
		
		// move sections, either on individual lines (for distinctLine == true) 
		// or continuing on the same line until maximum width is reached
		for (int i = 0; i < tokens.size(); ++i) {
			// determine the section to be moved; endToken is the Token following the last Token of the section (or null) 
			Token token = tokens.get(i);
			Token endToken = (i + 1 < tokens.size()) ? tokens.get(i + 1) : null;
			Token prevCode = token.getPrevCodeSibling();
			boolean isFirstSection = (i == 0 || command.firstCodeTokenIsKeyword("SORT") && prevCode != null && prevCode.isKeyword("BY")); 
			if (isFirstSection) {
				firstInstance = token;
				indent = (firstInstance.lineBreaks == 0) ? firstInstance.getPrev().getEndIndexInLine() + 1 : firstInstance.getStartIndexInLine();
			}

			// if the section to be moved contains line breaks, find the first Token after the first line break 
			Token firstTokenAfterLineBreak = null;
			Token testToken = token;
			while (testToken != endToken) {
				if (testToken != token && testToken.lineBreaks > 0) {
					firstTokenAfterLineBreak = testToken;
					break;
				}
				testToken = testToken.getNext();
			}
			
			// determine start and width of current section
			int oldStartIndex = token.getStartIndexInLine();
			int width = token.getMaxIndexInLine(endToken) - oldStartIndex;
			
			// move current Token
			int moveBy = 0;
			boolean tokenChanged = false;
			if (isFirstSection) {
				// for the first section, only condense multiple spaces, e.g. in "CLEAR:   lv_any_value, ..."
				if (token.lineBreaks == 0 && token.spacesLeft > 1) {
					moveBy = indent - oldStartIndex;
					tokenChanged = token.setWhitespace();
				}

			} else if (distinctLine || token.getPrev().isComment() || token.getPrev().getEndIndexInLine() + 1 + width > maxLineLength) {
				// move Token to the next line
				moveBy = indent - oldStartIndex;
				tokenChanged = token.setWhitespace(1, indent);
			
			} else {
				// append Token at the end of the previous line
				moveBy = token.getPrev().getEndIndexInLine() + 1 - oldStartIndex;
				tokenChanged = token.setWhitespace();
			}
			if (tokenChanged) {
				commandChanged = true;
			}
			
			// move further lines of the current section
			if (moveBy != 0 && firstTokenAfterLineBreak != null) {
				if (command.addIndent(moveBy, oldStartIndex, firstTokenAfterLineBreak, endToken, true)) {
					commandChanged = true;
				}
			}
		}
		
		return commandChanged;
	}
}
