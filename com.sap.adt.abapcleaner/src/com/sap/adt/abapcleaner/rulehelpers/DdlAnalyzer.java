package com.sap.adt.abapcleaner.rulehelpers;

import java.util.ArrayList;
import java.util.HashMap;

import com.sap.adt.abapcleaner.base.DDL;
import com.sap.adt.abapcleaner.base.StringUtil;
import com.sap.adt.abapcleaner.parser.Code;
import com.sap.adt.abapcleaner.parser.Command;
import com.sap.adt.abapcleaner.parser.Token;
import com.sap.adt.abapcleaner.parser.TokenSearch;
import com.sap.adt.abapcleaner.programbase.UnexpectedSyntaxBeforeChanges;

public class DdlAnalyzer {
	private static String getKey(String identifier) {
		return identifier.toUpperCase();
	}
	
	private static class View {
		private final String fileName;
		private final String entityName;
		private final boolean isViewEntity;
		@SuppressWarnings("unused")
		private final int viewPart;
		private final View mainView; // the first part of a view that contains UNION / EXCEPT / INTERSECT
		
		private final ArrayList<Parameter> parametersInOrder = new ArrayList<>();
		private final ArrayList<DataSource> dataSourcesInOrder = new ArrayList<>();
		private final ArrayList<Field> fieldsInOrder = new ArrayList<>();
		
		private final HashMap<String, Parameter> parameterOfName = new HashMap<>(); 
		private final HashMap<String, DataSource> dataSourceOfAlias = new HashMap<>(); 
		private final HashMap<String, Field> fieldOfName = new HashMap<>(); 
		
		private View(String fileName, String entityName, boolean isViewEntity, int viewPart, View mainView) {
			this.fileName = fileName;
			this.entityName = entityName;
			this.isViewEntity = isViewEntity;
			this.viewPart = viewPart;
			this.mainView = mainView;
		}
		
		private DataSource getDataSource(String key) {
			return dataSourceOfAlias.get(key);
		}

		public Field findFieldInDataSources(String fieldName, boolean inferFromUnknownOnlySource) {
			String key = getKey(fieldName);

			DataSource onlyDataSource = (dataSourcesInOrder.size() == 0) ? null : dataSourcesInOrder.get(0);
			for (DataSource checkDataSource : dataSourcesInOrder) {
				if (checkDataSource.isAssociation)
					continue;
				if (checkDataSource != onlyDataSource)
					onlyDataSource = null;
				if (checkDataSource.view == null)
					continue;
				Field testField = checkDataSource.view.fieldOfName.get(key);
				if (testField != null) {
					return testField;
				}
			}
			
			// if there is only one data source, this must be the one, even if it is out of sight
			if (inferFromUnknownOnlySource && onlyDataSource != null && onlyDataSource.view == null)
				return new Field(onlyDataSource, fieldName);

			return null;
		}
	}

	private static class DataSource {
		private final String entityName;
		@SuppressWarnings("unused")
		private final String alias;
		@SuppressWarnings("unused")
		private final boolean isJoin;
		private final boolean isAssociation;
		private View view;
		
		private DataSource(String entityName, String alias, boolean isJoin, boolean isAssociation) {
			this.entityName = entityName;
			this.alias = alias;
			this.isJoin = isJoin;
			this.isAssociation = isAssociation;
		}
	}
	
	private static class Parameter {
		@SuppressWarnings("unused")
		private final View parentView;
		@SuppressWarnings("unused")
		private final int positionInView;
		@SuppressWarnings("unused")
		private final String name;
		@SuppressWarnings("unused")
		private final String type;
		private HashMap<String, Annotation> annotations = new HashMap<>();

		private Parameter(View parentView, String name, String type) {
			this.parentView = parentView;
			this.positionInView = parentView.fieldOfName.size();
			this.name = name;
			this.type = type;
		}
	}

	/** represents a field or an exposed association */
	private static class Field {
		private final View parentView;
		private final DataSource dataSource; // for inferred fields only
		private final int positionInView;
		private final String name;
		private final boolean isVirtual;
		private final String sourcePath; // simplified, e.g. by omitting all path expressions in brackets [...]
		private final boolean isExposedAssociation;
		
		// statistics
		private final int literalCount;
		private final int castCount;
		private final int caseCount;
		private final int functionCount; // cp. DDL.builtInDdlFunctions
		private final int aggregationCount; // cp. DDL.aggregationFunctions (Tokens are keywords)
		private HashMap<String, Annotation> annotations = new HashMap<>(); // note that annotation.parentField may point to a source field!

		private Field(View parentView, String name, boolean isVirtual, String sourcePath, int literalCount, int castCount, int caseCount, int ddlFunctionCount, int ddlAggregationCount) {
			this.parentView = parentView;
			this.dataSource = null;
			this.positionInView = parentView.fieldOfName.size();
			this.name = name;
			this.isVirtual = isVirtual;
			this.sourcePath = sourcePath;
			int dotPos = sourcePath.lastIndexOf('.');
			this.isExposedAssociation = (dotPos + 1 < sourcePath.length()) ? (sourcePath.charAt(dotPos + 1) == '_') : false;
			
			this.literalCount = literalCount;
			this.castCount = castCount;
			this.caseCount = caseCount;
			this.functionCount = ddlFunctionCount;
			this.aggregationCount = ddlAggregationCount;
		}
		
		/** constructor for inferred fields from unknown data sources */
		private Field(DataSource dataSource, String name) {
			this.parentView = null;
			this.dataSource = dataSource;
			this.positionInView = -1;
			this.name = name;
			this.isVirtual = false;
			this.sourcePath = dataSource.entityName;
			this.isExposedAssociation = false;
			
			this.literalCount = 0;
			this.castCount = 0;
			this.caseCount = 0;
			this.functionCount = 0;
			this.aggregationCount = 0;
		}

		private boolean wasInferred() {
			return (dataSource != null);
		}
		
		private Annotation findAnyAnnotation(String[] paths) {
			if (paths == null)
				return null;
			for (String path : paths) {
				Annotation annotation = annotations.get(getKey(path));
				if (annotation != null) {
					return annotation;
				}
			}
			return null;
		}
	}
	
	private static class Annotation {
		@SuppressWarnings("unused")
		private final Parameter parentParameter;
		private final Field parentField;
		private final String path; // e.g. "Semantics.amount.currencyCode" or "Semantics.quantity.unitOfMeasure"
		private final String value; // the element name which this annotation refers to
		@SuppressWarnings("unused")
		private final boolean isElementRef;
		@SuppressWarnings("unused")
		private final boolean isParameterRef;
		@SuppressWarnings("unused")
		private final boolean isAssociationRef;
		@SuppressWarnings("unused")
		private final boolean isEntityRef;
		
		private Annotation(Parameter parentParameter, Field parentField, String path, String value) {
			this.parentParameter = parentParameter;
			this.parentField = parentField;
			this.path = path;
			this.value = value;
			this.isElementRef = DDL.isKnownElementRefAnnotation(path);
			this.isParameterRef = DDL.isKnownParameterRefAnnotation(path);
			this.isAssociationRef = DDL.isKnownAssociationRefAnnotation(path);
			this.isEntityRef = DDL.isKnownEntityRefAnnotation(path);
		}
	}

	// -------------------------------------------------------------------------

	private HashMap<String, View> viewOfEntityName = new HashMap<>();
	private ArrayList<View> viewsInOrder = new ArrayList<>(); // also contains UNION etc. parts as <entity name>+1 etc.
	
	public void addFile(String fileName, Code code) {
		if (code == null || !code.isDdlOrDcl())
			return;
		
		View mainView = null;
		boolean isViewEntity = false;
		View view = null;
		String entityName = "";
		
		int viewPart = 0; // is increased for each UNION / EXCEPT / INTERSECT, which defines new aliases
		Command command = code.firstCommand;
		DdlAnnotationScope scope = new DdlAnnotationScope();
		while (command != null) {
			if (command.isCommentLine()) {
				command = command.getNext();
				continue;
			}
			
			// create the View instance (or a new instance after UNION etc.)
			Token entityNameToken = command.getDdlOrDclEntityNameToken();
			if (entityNameToken != null) {
				entityName = entityNameToken.getText();
				isViewEntity = entityNameToken.getPrevCodeSibling().isKeyword("ENTITY");
				
				view = new View(fileName, entityName, isViewEntity, viewPart, null);
				mainView = view;
				viewsInOrder.add(view);
				viewOfEntityName.put(getKey(entityName), view);

				// TODO: consider name lists! 
			
			} else if (command.startsDdlUnionEtc()) {
				++viewPart;
				String entityNameWithSuffix = entityName + "+" + String.valueOf(viewPart);
				view = new View(fileName, entityNameWithSuffix, isViewEntity, viewPart, mainView);
				viewsInOrder.add(view);
				// viewOfEntityName.put(getKey(entityNameWithSuffix), view);
			} // do NOT attach with "else if", because UNION ... also contains FROM ...
			
			if (view != null) { 
				// read parameters
				if (command.isDdlParametersElement()) { 
					addParameter(view, command, scope);
					scope = new DdlAnnotationScope();
				} 
				
				// read data sources: FROM clause, JOINs and ASSOCIATIONs
				if (command.startsDdlFromClause()) {
					addDataSource(view, command.getDdlFromDataSource(), false, false);
				
				} else if (command.startsDdlJoin()) {
					addDataSource(view, command.getDdlJoinTarget(), true, false);
					
				} else if (command.startsDdlAssociation()) {
					addDataSource(view, command.getDdlAssociationTarget(), false, true);
				}
				
				// read fields
				if (command.isDdlSelectElement()) { 
					addField(view, command, scope);
					scope = new DdlAnnotationScope();
				} 
			}
			
			if (command.isDdlAnnotation()) {
				try {
					scope.add(command);
				} catch (UnexpectedSyntaxBeforeChanges e) {
				}
			}
			command = command.getNext();
		}
	}

	private void addDataSource(View view, Token entityNameToken, boolean isJoin, boolean isAssociation) {
		if (entityNameToken == null)
			return; // TODO
		
		Token asToken = entityNameToken.getLastTokenOnSiblings(true, TokenSearch.ASTERISK, "AS");
		String alias = "";
		if (asToken != null && asToken.isKeyword()) {
			Token aliasToken = asToken.getNextCodeSibling();
			alias = aliasToken.getText();
		} else {
			alias = entityNameToken.getText();
		}

		DataSource dataSource = new DataSource(entityNameToken.getText(), alias, isJoin, isAssociation);
		view.dataSourcesInOrder.add(dataSource);
		view.dataSourceOfAlias.put(getKey(alias), dataSource);
	}
	
	private void addParameter(View view, Command command, DdlAnnotationScope scope) {
		Token nameToken = command.getFirstCodeToken();
		String name = nameToken.getText();
		
		Token colonToken = nameToken.getNextCodeSibling();
		String typeName = "";
		if (colonToken != null) 
			typeName = colonToken.getNextCodeSibling().getText();
		
		Parameter parameter = new Parameter(view, name, typeName);
		view.parametersInOrder.add(parameter);
		view.parameterOfName.put(getKey(name), parameter);
		
		addAnnotations(parameter, null, scope);
	}

	private void addField(View view, Command command, DdlAnnotationScope scope) {
		Token firstCode = command.getFirstCodeToken();
		Token lastToken = command.getLastNonCommentToken();
		
		Token pathEnd = lastToken.textEqualsAny(DDL.COMMA_SIGN_STRING, DDL.SEMICOLON_SIGN_STRING) ? lastToken : null;
		Token asToken = firstCode.getLastTokenOnSiblings(true, TokenSearch.ASTERISK, "AS");
		Token exprEnd = null;
		String fieldName;
		boolean isVirtual = false;
		if (asToken != null) {
			fieldName = asToken.getNextCodeSibling().getText();
			pathEnd = asToken;
			exprEnd = asToken;
		} else if (firstCode.isKeyword("VIRTUAL")) {
			isVirtual = true;
			Token colonToken = firstCode.getLastTokenOnSiblings(true, TokenSearch.ASTERISK, DDL.COLON_SIGN_STRING);
			if (colonToken != null) {
				pathEnd = colonToken;
				exprEnd = colonToken;
			}
			Token fieldNameToken = firstCode.getNextCodeSibling();
			fieldName = fieldNameToken.getText();
		} else {
			// skip keywords and colons etc. at the end, e.g. "_assoc1[._assoc2][._assoc3].field_name [AS alias] : LOCALIZED"
			Token fieldNameToken = command.getLastNonCommentToken();
			while (fieldNameToken != null && (fieldNameToken.isKeyword() || fieldNameToken.textEqualsAny(DDL.COMMA_SIGN_STRING, DDL.SEMICOLON_SIGN_STRING, DDL.COLON_SIGN_STRING))) {
				fieldNameToken = fieldNameToken.getPrevCodeSibling();
			}
			fieldName = fieldNameToken.getText();
			int dotPos = fieldName.lastIndexOf('.');
			if (dotPos >= 0) {
				fieldName = fieldName.substring(dotPos + 1);
			}
			exprEnd = null;
		}
		
		// determine the source path of this field
		StringBuilder sbPath = new StringBuilder();
		Token nextCode = firstCode.getNextCodeSibling();
		Token pathStart;
		if ((firstCode.isKeyword("CAST") || firstCode.textEqualsAny(DDL.aggregationFunctions) && !firstCode.textEquals("COUNT")) 
				&& nextCode.textEquals(DDL.PARENS_OPEN_STRING) && nextCode.getNextCodeSibling().getNextCodeSibling() == pathEnd) {
			// move inside of the CAST or aggregation function and read the field name until "AS" or the end of the parenthesis
			pathStart = nextCode.getFirstChild().getNextWhileComment();
			pathEnd = firstCode.isKeyword("CAST") ? pathStart.getLastTokenOnSiblings(true, TokenSearch.ASTERISK, "AS") : null;
		} else if (firstCode.isAnyKeyword("KEY", "VIRTUAL")) {
			pathStart = firstCode.getNextCodeSibling();
		} else {
			pathStart = firstCode;
		}
		if (pathStart != null) {
			Token token = pathStart;
			while (token != null && token != pathEnd) {
				nextCode = token.getNextCodeSibling();
				if (token.textEquals(DDL.BRACKET_OPEN_STRING)) {
					// continue after the [...] filter
					token = token.getNextSibling();

				} else if (token.isLiteral() || token.textEqualsAny(DDL.arithmeticOperators) 
						|| DDL.isBuiltInDdlFunction(token.getText()) && nextCode != null && nextCode.textEquals(DDL.PARENS_OPEN_STRING)) {
					// discard the path
					sbPath = new StringBuilder();
					break;
				
				} else if (token.isIdentifier() || token.textEquals(DDL.DOT_SIGN_STRING)) {
					// add the Token to the path
					sbPath.append(token.getText());
				}
				token = token.getNextCodeSibling();
			}
		}
		
		// determine the number of cast(...), case ... and DDL functions used
		int literalCount = 0;
		int castCount = 0;
		int caseCount = 0;
		int functionCount = 0; // cp. DDL.builtInDdlFunctions
		int aggregationCount = 0; // cp. DDL.aggregationFunctions
		Token token = firstCode;
		while (token != exprEnd) {
			Token next = token.getNextCodeSibling();
			if (token.isLiteral()) {
				++literalCount;
			} else if (token.isKeyword("CAST")) {
				++castCount;
			} else if (token.isKeyword("CASE")) {
				++caseCount;
			} else if (next != null && next.textEquals(DDL.PARENS_OPEN_STRING) && DDL.isBuiltInDdlFunction(token.getText())) { 
				++functionCount;
			} else if (token.isAnyKeyword(DDL.aggregationFunctions)) {
				++aggregationCount;
			}
			token = token.getNextCodeToken();
		}

		Field field = new Field(view, fieldName, isVirtual, sbPath.toString(), literalCount, castCount, caseCount, functionCount, aggregationCount);
		view.fieldsInOrder.add(field);
		view.fieldOfName.put(getKey(fieldName), field);
		
		addAnnotations(null, field, scope);
	}

	private void addAnnotations(Parameter parameter, Field field, DdlAnnotationScope scope) {
		for (DdlAnnotation annotation : scope.getAnnotations()) {
			String path = annotation.getPath();
			Annotation newAnnotation = new Annotation(parameter, field, path, annotation.getValue());
			HashMap<String, Annotation> annotations = (parameter != null) ? parameter.annotations : field.annotations;
			annotations.put(getKey(path), newAnnotation);
		}
	}

	public void finishBuild() {
		// determine the View instances behind the data sources, if available
		for (View view : viewsInOrder) {
			for (DataSource dataSource : view.dataSourcesInOrder) {
				dataSource.view = viewOfEntityName.get(getKey(dataSource.entityName));
			}
		}		
	}

	public void analyzeInheritedAnnotations(String[] annotationPaths) {
		boolean stopAtAggregation = true;
		
		for (View view : viewsInOrder) {
			for (Field field : view.fieldsInOrder) {
				// does the field already have the annotations directly?
				if (field.findAnyAnnotation(annotationPaths) != null)
					continue;
				// in a view entity, the annotation of the main select list is 'inherited'
				if (view.isViewEntity && view.mainView != null) {
					Field mainViewField = view.mainView.fieldOfName.get(getKey(field.name));
					if (mainViewField != null) { // pro forma
						Annotation mainFieldAnnotation = mainViewField.findAnyAnnotation(annotationPaths);
						if (mainFieldAnnotation != null) {
							field.annotations.put(getKey(mainFieldAnnotation.path), mainFieldAnnotation);
							continue;
						}
					}
				}
				if (stopAtAggregation && field.aggregationCount > 0)
					continue;
				
				Field sourceField = getSourceField(field);
				while (sourceField != null && !sourceField.wasInferred()) {
					Annotation annotation = sourceField.findAnyAnnotation(annotationPaths);
					if (annotation != null) {
						// add the inherited annotations to the existing ones; 
						// with annotation.parentField, it can always be determined whether the annotation was inherited
						field.annotations.put(getKey(annotation.path), annotation);
						break;
					}
					if (stopAtAggregation && sourceField.aggregationCount > 0)
						break;
					sourceField = getSourceField(sourceField);
				}
			}
		}
	}

	private Field getSourceField(Field field) {
		String[] pathBits = StringUtil.split(field.sourcePath, '.', true);
		View view = field.parentView;
		DataSource finalDataSource = null; // if no View instance is known for a DataSource at the end of the pathBits, at least this instance is filled

		for (int i = 0; i < pathBits.length; ++i) {
			boolean isLastBit = (i + 1 == pathBits.length);
			String pathBitKey = getKey(pathBits[i]);
			
			if (isLastBit) {
				if (field.isExposedAssociation) {
					// this branch is used when resolving exposed associations recursively (see below); 
					// wrap the data source of the association in a field, just to satisfy the return type
					DataSource dataSource = (view == null) ? null : view.dataSourceOfAlias.get(pathBitKey);
					return (dataSource == null) ? null : new Field(dataSource, ""); 

				} else if (view != null) {
					if (view == field.parentView) {
						// without explicit alias, search for an underlying data source (excluding associations) that offers this field name
						return view.findFieldInDataSources(pathBits[i], true);
					} else {
						return view.fieldOfName.get(pathBitKey);
					}
					
				} else { // use finalDataSource, which must be != null now
					return new Field(finalDataSource, pathBits[i]);
				}
			} 
			
			if (view == null)
				break;

			// find the data source, either in this view ...
			DataSource dataSource = view.getDataSource(pathBitKey);
			if (dataSource == null && pathBitKey.startsWith(DDL.UNDERSCORE_STRING)) {
				// ... or as an exposed associations from an underlying view
				Field exposedAssociation = view.findFieldInDataSources(pathBitKey, true);
				if (exposedAssociation == null) 
					break;

				// the exposedAssociation may itself contain a path, which must be resolved recursively to find its data source
				Field associationSource = getSourceField(exposedAssociation);
				if (associationSource == null) 
					break;
				dataSource = associationSource.dataSource;
			}
			if (dataSource == null) 
				break;

			// if the view of this data source is not known, the data source can still be used as the "final data source"  
			view = dataSource.view;
			if (view == null) {
				finalDataSource = dataSource;
			}
		}
		return null;
	}

	public String getResult(String[] annotationPaths) {
		final String LINE_SEP = System.lineSeparator();
		final String TAB = "\t";
		
		StringBuilder sb = new StringBuilder();

		// create header line 
		sb.append("ID|Package|View|Pos|Field|Literals|CASTs|CASEs|Functions|Aggregations|Direct Source View|Direct Source Field".replace('|', '\t'));
		// annotations of interest
		if (annotationPaths != null) {
			for (String annotationPath : annotationPaths) {
				int dotPos = annotationPath.lastIndexOf('.');
				String outputName = dotPos > 0 ? annotationPath.substring(dotPos + 1) : annotationPath;
				sb.append(TAB).append(outputName);
				sb.append(TAB).append("Source of " + outputName);
			}
		}
		sb.append(LINE_SEP);

		// field lines
		for (View view : viewsInOrder) {
			String pack = "";
			int packageSepPos = view.fileName.indexOf(" - ");
			if (packageSepPos > 0) {
				pack = view.fileName.substring(0, packageSepPos); 
			}
			for (Field field : view.fieldsInOrder) {
				if (field.isExposedAssociation)
					continue;
				
				// ID and package
				sb.append(view.entityName + "." + field.name);
				sb.append(TAB).append(pack);
				
				// view and field
				sb.append(TAB).append(view.entityName);
				sb.append(TAB).append(String.valueOf(field.positionInView));
				sb.append(TAB).append(field.isVirtual ? "(" + field.name + ")" : field.name);
				
				// statistics
				sb.append(TAB).append(String.valueOf(field.literalCount));
				sb.append(TAB).append(String.valueOf(field.castCount));
				sb.append(TAB).append(String.valueOf(field.caseCount));
				sb.append(TAB).append(String.valueOf(field.functionCount));
				sb.append(TAB).append(String.valueOf(field.aggregationCount));

				// source view and field
				Field sourceField = getSourceField(field);
				if (sourceField == null) {
					sb.append(TAB).append("");
					sb.append(TAB).append("");
				} else if (sourceField.wasInferred()) {
					sb.append(TAB).append("(" + sourceField.dataSource.entityName + ")");
					sb.append(TAB).append(sourceField.name);
				} else {
					sb.append(TAB).append(sourceField.parentView.entityName);
					sb.append(TAB).append(sourceField.name);
				}

				// annotations of interest
				if (annotationPaths != null) {
					for (String annotationPath : annotationPaths) {
						Annotation annotation = field.annotations.get(getKey(annotationPath));
						if (annotation == null) {
							sb.append(TAB).append("");
							sb.append(TAB).append("");
						} else {
							sb.append(TAB).append(annotation.value);
							
							String sourceOfInheritedAnno = "";
							if (annotation.parentField != field) 
								sourceOfInheritedAnno = annotation.parentField.parentView.entityName + "." + annotation.parentField.name;
							sb.append(TAB).append(sourceOfInheritedAnno);
						}
					}
				}
				
				sb.append(LINE_SEP);
			}
		}
		return sb.toString();
	}
}
