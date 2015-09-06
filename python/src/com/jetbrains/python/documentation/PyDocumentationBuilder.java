/*
 * Copyright 2000-2014 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.python.documentation;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.text.LineTokenizer;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.codeStyle.CodeStyleSettingsManager;
import com.intellij.psi.util.PsiTreeUtil;
import com.jetbrains.python.PyBundle;
import com.jetbrains.python.PyNames;
import com.jetbrains.python.PythonFileType;
import com.jetbrains.python.console.PyConsoleUtil;
import com.jetbrains.python.psi.*;
import com.jetbrains.python.psi.impl.PyBuiltinCache;
import com.jetbrains.python.psi.impl.PyCallExpressionHelper;
import com.jetbrains.python.psi.impl.PyPsiUtils;
import com.jetbrains.python.psi.resolve.PyResolveContext;
import com.jetbrains.python.psi.resolve.QualifiedResolveResult;
import com.jetbrains.python.psi.resolve.RootVisitor;
import com.jetbrains.python.psi.resolve.RootVisitorHost;
import com.jetbrains.python.psi.types.*;
import com.jetbrains.python.toolbox.ChainIterable;
import com.jetbrains.python.toolbox.Maybe;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.jetbrains.python.documentation.DocumentationBuilderKit.*;

class PyDocumentationBuilder {
  private final PsiElement myElement;
  private final PsiElement myOriginalElement;
  private ChainIterable<String> myResult;
  private ChainIterable<String> myProlog;      // sequence for reassignment info, etc
  private ChainIterable<String> myBody;        // sequence for doc string
  private ChainIterable<String> myEpilog;      // sequence for doc "copied from" notices and such

  private static final Pattern ourSpacesPattern = Pattern.compile("^\\s+");
  private ChainIterable<String> myReassignmentChain;

  public PyDocumentationBuilder(PsiElement element, PsiElement originalElement) {
    myElement = element;
    myOriginalElement = originalElement;
    myResult = new ChainIterable<String>();
    myProlog = new ChainIterable<String>();
    myBody = new ChainIterable<String>();
    myEpilog = new ChainIterable<String>();

    myResult.add(myProlog).addWith(TagCode, myBody).add(myEpilog); // pre-assemble; then add stuff to individual cats as needed
    myResult = wrapInTag("html", wrapInTag("body", myResult));
    myReassignmentChain = new ChainIterable<String>();
  }

  private boolean buildForProperty(PsiElement elementDefinition, @Nullable final PsiElement outerElement,
                                   @NotNull final TypeEvalContext context) {
    if (myOriginalElement == null) {
      return false;
    }
    final String elementName = myOriginalElement.getText();
    if (!PyNames.isIdentifier(elementName)) {
      return false;
    }
    if (!(outerElement instanceof PyQualifiedExpression)) {
      return false;
    }
    final PyExpression qualifier = ((PyQualifiedExpression)outerElement).getQualifier();
    if (qualifier == null) {
      return false;
    }
    final PyType type = context.getType(qualifier);
    if (!(type instanceof PyClassType)) {
      return false;
    }
    final PyClass cls = ((PyClassType)type).getPyClass();
    final Property property = cls.findProperty(elementName, true, null);
    if (property == null) {
      return false;
    }

    final AccessDirection direction = AccessDirection.of((PyElement)outerElement);
    final Maybe<PyCallable> accessor = property.getByDirection(direction);
    myProlog.addItem("property ").addWith(TagBold, $().addWith(TagCode, $(elementName)))
            .addItem(" of ").add(PythonDocumentationProvider.describeClass(cls, TagCode, true, true));
    if (accessor.isDefined() && property.getDoc() != null) {
      myBody.addItem(": ").addItem(property.getDoc()).addItem(BR);
    }
    else {
      final PyCallable getter = property.getGetter().valueOrNull();
      if (getter != null && getter != myElement && getter instanceof PyFunction) {
        // not in getter, getter's doc comment may be useful
        final PyStringLiteralExpression docstring = ((PyFunction)getter).getDocStringExpression();
        if (docstring != null) {
          myProlog
            .addItem(BR).addWith(TagItalic, $("Copied from getter:")).addItem(BR)
            .addItem(docstring.getStringValue())
          ;
        }
      }
      myBody.addItem(BR);
    }
    myBody.addItem(BR);
    if (accessor.isDefined() && accessor.value() == null) elementDefinition = null;
    final String accessorKind = getAccessorKind(direction);
    if (elementDefinition != null) {
      myEpilog.addWith(TagSmall, $(BR, BR, accessorKind, " of property")).addItem(BR);
    }

    if (!(elementDefinition instanceof PyDocStringOwner)) {
      myBody.addWith(TagItalic, elementDefinition != null ? $("Declaration: ") : $(accessorKind + " is not defined.")).addItem(BR);
      if (elementDefinition != null) {
        myBody.addItem(combUp(PyUtil.getReadableRepr(elementDefinition, false)));
      }
    }
    return true;
  }

  @NotNull
  private static String getAccessorKind(@NotNull final AccessDirection dir) {
    final String accessorKind;
    if (dir == AccessDirection.READ) {
      accessorKind = "Getter";
    }
    else if (dir == AccessDirection.WRITE) {
      accessorKind = "Setter";
    }
    else {
      accessorKind = "Deleter";
    }
    return accessorKind;
  }


  public String build() {
    final TypeEvalContext context = TypeEvalContext.userInitiated(myElement.getProject(), myElement.getContainingFile());
    final PsiElement outerElement = myOriginalElement != null ? myOriginalElement.getParent() : null;

    final PsiElement elementDefinition = resolveToDocStringOwner();
    final boolean isProperty = buildForProperty(elementDefinition, outerElement, context);

    if (myProlog.isEmpty() && !isProperty && !isAttribute()) {
      myProlog.add(myReassignmentChain);
    }

    if (elementDefinition instanceof PyDocStringOwner) {
      buildFromDocstring(elementDefinition, isProperty);
    }
    else if (isAttribute()) {
      buildFromAttributeDoc();
    }
    else if (elementDefinition instanceof PyNamedParameter) {
      myBody.addItem(combUp("Parameter " + PyUtil.getReadableRepr(elementDefinition, false)));
      boolean typeFromDocstringAdded = addTypeAndDescriptionFromDocstring((PyNamedParameter)elementDefinition);
      if (outerElement instanceof PyExpression) {
        PyType type = context.getType((PyExpression)outerElement);
        if (type != null) {
          String typeString = null;
          if (type instanceof PyDynamicallyEvaluatedType) {
            if (!typeFromDocstringAdded) {
              //don't add dynamic type if docstring type specified
              typeString = "\nDynamically inferred type: ";
            }
          }
          else {
            if (outerElement.getReference() != null) {
              PsiElement target = outerElement.getReference().resolve();

              if (target instanceof PyTargetExpression) {
                final String targetName = ((PyTargetExpression)target).getName();
                if (targetName != null && targetName.equals(((PyNamedParameter)elementDefinition).getName())) {
                  typeString = "\nReassigned value has type: ";
                }
              }
            }
          }
          if (typeString == null && !typeFromDocstringAdded) {
            typeString = "\nInferred type: ";
          }
          if (typeString != null) {
            myBody
              .addItem(combUp(typeString));
            PythonDocumentationProvider.describeTypeWithLinks(myBody, elementDefinition, type, context);
          }
        }
      }
    }
    else if (elementDefinition != null && outerElement instanceof PyReferenceExpression) {
      myBody.addItem(combUp("\nInferred type: "));
      PythonDocumentationProvider.describeExpressionTypeWithLinks(myBody, (PyReferenceExpression)outerElement, context);
    }
    if (myBody.isEmpty() && myEpilog.isEmpty()) {
      return null; // got nothing substantial to say!
    }
    else {
      return myResult.toString();
    }
  }

  private void buildFromDocstring(@NotNull final PsiElement elementDefinition, boolean isProperty) {
    PyClass pyClass = null;
    final PyStringLiteralExpression docStringExpression = ((PyDocStringOwner)elementDefinition).getDocStringExpression();

    if (elementDefinition instanceof PyClass) {
      pyClass = (PyClass)elementDefinition;
      myBody.add(PythonDocumentationProvider.describeDecorators(pyClass, TagItalic, BR, LCombUp));
      myBody.add(PythonDocumentationProvider.describeClass(pyClass, TagBold, true, false));
    }
    else if (elementDefinition instanceof PyFunction) {
      PyFunction pyFunction = (PyFunction)elementDefinition;
      if (!isProperty) {
        pyClass = pyFunction.getContainingClass();
        if (pyClass != null) {
          myBody.addWith(TagSmall, PythonDocumentationProvider.describeClass(pyClass, TagCode, true, true)).addItem(BR).addItem(BR);
        }
      }
      myBody.add(PythonDocumentationProvider.describeDecorators(pyFunction, TagItalic, BR, LCombUp))
            .add(PythonDocumentationProvider.describeFunction(pyFunction, TagBold, LCombUp));
      if (docStringExpression == null) {
        addInheritedDocString(pyFunction, pyClass);
      }
    }
    else if (elementDefinition instanceof PyFile) {
      addModulePath((PyFile)elementDefinition);
    }
    if (docStringExpression != null) {
      myBody.addItem(BR);
      addFormattedDocString(myElement, docStringExpression.getStringValue(), myBody, myEpilog);
    }
  }

  private boolean isAttribute() {
    return myElement instanceof PyTargetExpression && PyUtil.isAttribute((PyTargetExpression)myElement);
  }

  @Nullable
  private PsiElement resolveToDocStringOwner() {
    // here the ^Q target is already resolved; the resolved element may point to intermediate assignments
    if (myElement instanceof PyTargetExpression) {
      final String targetName = myElement.getText();
      myReassignmentChain.addWith(TagSmall, $(PyBundle.message("QDOC.assigned.to.$0", targetName)).addItem(BR));
      final PyExpression assignedValue = ((PyTargetExpression)myElement).findAssignedValue();
      if (assignedValue instanceof PyReferenceExpression) {
        final PsiElement resolved = resolveWithoutImplicits((PyReferenceExpression)assignedValue);
        if (resolved != null) {
          return resolved;
        }
      }
      return assignedValue;
    }
    if (myElement instanceof PyReferenceExpression) {
      myReassignmentChain.addWith(TagSmall, $(PyBundle.message("QDOC.assigned.to.$0", myElement.getText())).addItem(BR));
      return resolveWithoutImplicits((PyReferenceExpression)myElement);
    }
    // it may be a call to a standard wrapper
    if (myElement instanceof PyCallExpression) {
      final PyCallExpression call = (PyCallExpression)myElement;
      final Pair<String, PyFunction> wrapInfo = PyCallExpressionHelper.interpretAsModifierWrappingCall(call, myOriginalElement);
      if (wrapInfo != null) {
        String wrapperName = wrapInfo.getFirst();
        PyFunction wrappedFunction = wrapInfo.getSecond();
        myReassignmentChain.addWith(TagSmall, $(PyBundle.message("QDOC.wrapped.in.$0", wrapperName)).addItem(BR));
        return wrappedFunction;
      }
    }
    return myElement;
  }

  private static PsiElement resolveWithoutImplicits(final PyReferenceExpression element) {
    final QualifiedResolveResult resolveResult = element.followAssignmentsChain(PyResolveContext.noImplicits());
    return resolveResult.isImplicit() ? null : resolveResult.getElement();
  }

  private void addInheritedDocString(PyFunction fun, PyClass cls) {
    boolean not_found = true;
    String meth_name = fun.getName();
    if (cls != null && meth_name != null) {
      final boolean is_constructor = PyNames.INIT.equals(meth_name);
      // look for inherited and its doc
      Iterable<PyClass> classes = cls.getAncestorClasses(null);
      if (is_constructor) {
        // look at our own class again and maybe inherit class's doc
        classes = new ChainIterable<PyClass>(cls).add(classes);
      }
      for (PyClass ancestor : classes) {
        PyStringLiteralExpression doc_elt = null;
        PyFunction inherited = null;
        boolean is_from_class = false;
        if (is_constructor) doc_elt = cls.getDocStringExpression();
        if (doc_elt != null) {
          is_from_class = true;
        }
        else {
          inherited = ancestor.findMethodByName(meth_name, false);
        }
        if (inherited != null) {
          doc_elt = inherited.getDocStringExpression();
        }
        if (doc_elt != null) {
          String inherited_doc = doc_elt.getStringValue();
          if (inherited_doc.length() > 1) {
            myEpilog.addItem(BR).addItem(BR);
            String ancestor_name = ancestor.getName();
            String marker = (cls == ancestor) ? PythonDocumentationProvider.LINK_TYPE_CLASS : PythonDocumentationProvider.LINK_TYPE_PARENT;
            final String ancestor_link =
              $().addWith(new LinkWrapper(marker + ancestor_name), $(ancestor_name)).toString();
            if (is_from_class) {
              myEpilog.addItem(PyBundle.message("QDOC.copied.from.class.$0", ancestor_link));
            }
            else {
              myEpilog.addItem(PyBundle.message("QDOC.copied.from.$0.$1", ancestor_link, meth_name));
            }
            myEpilog.addItem(BR).addItem(BR);
            ChainIterable<String> formatted = new ChainIterable<String>();
            ChainIterable<String> unformatted = new ChainIterable<String>();
            addFormattedDocString(fun, inherited_doc, formatted, unformatted);
            myEpilog.addWith(TagCode, formatted).add(unformatted);
            not_found = false;
            break;
          }
        }
      }

      if (not_found) {
        // above could have not worked because inheritance is not searched down to 'object'.
        // for well-known methods, copy built-in doc string.
        // TODO: also handle predefined __xxx__ that are not part of 'object'.
        if (PyNames.UnderscoredAttributes.contains(meth_name)) {
          addPredefinedMethodDoc(fun, meth_name);
        }
      }
    }
  }

  private void addPredefinedMethodDoc(PyFunction fun, String meth_name) {
    PyClassType objtype = PyBuiltinCache.getInstance(fun).getObjectType(); // old- and new-style classes share the __xxx__ stuff
    if (objtype != null) {
      PyClass objcls = objtype.getPyClass();
      PyFunction obj_underscored = objcls.findMethodByName(meth_name, false);
      if (obj_underscored != null) {
        PyStringLiteralExpression predefined_doc_expr = obj_underscored.getDocStringExpression();
        String predefined_doc = predefined_doc_expr != null ? predefined_doc_expr.getStringValue() : null;
        if (predefined_doc != null && predefined_doc.length() > 1) { // only a real-looking doc string counts
          addFormattedDocString(fun, predefined_doc, myBody, myBody);
          myEpilog.addItem(BR).addItem(BR).addItem(PyBundle.message("QDOC.copied.from.builtin"));
        }
      }
    }
  }

  private static void addFormattedDocString(PsiElement element, @NotNull String docstring,
                                            ChainIterable<String> formattedOutput, ChainIterable<String> unformattedOutput) {
    final Project project = element.getProject();

    List<String> formatted = PyStructuredDocstringFormatter.formatDocstring(element, docstring);
    if (formatted != null) {
      unformattedOutput.add(formatted);
      return;
    }

    boolean isFirstLine;
    final List<String> result = new ArrayList<String>();
    String[] lines = removeCommonIndentation(docstring);

    // reconstruct back, dropping first empty fragment as needed
    isFirstLine = true;
    int tabSize = CodeStyleSettingsManager.getSettings(project).getTabSize(PythonFileType.INSTANCE);
    for (String line : lines) {
      if (isFirstLine && ourSpacesPattern.matcher(line).matches()) continue; // ignore all initial whitespace
      if (isFirstLine) {
        isFirstLine = false;
      }
      else {
        result.add(BR);
      }
      int leadingTabs = 0;
      while (leadingTabs < line.length() && line.charAt(leadingTabs) == '\t') {
        leadingTabs++;
      }
      if (leadingTabs > 0) {
        line = StringUtil.repeatSymbol(' ', tabSize * leadingTabs) + line.substring(leadingTabs);
      }
      result.add(combUp(line));
    }
    formattedOutput.add(result);
  }

  /**
   * Adds type and description representation from function docstring
   *
   * @param parameter parameter of a function
   * @return true if type from docstring was added
   */
  private boolean addTypeAndDescriptionFromDocstring(@NotNull PyNamedParameter parameter) {
    PyFunction function = PsiTreeUtil.getParentOfType(parameter, PyFunction.class);
    if (function != null) {
      final String docString = PyPsiUtils.strValue(function.getDocStringExpression());
      Pair<String, String> typeAndDescr = getTypeAndDescr(docString, parameter);

      String type = typeAndDescr.first;
      String desc = typeAndDescr.second;

      if (type != null) {
        final PyType pyType = PyTypeParser.getTypeByName(parameter, type);
        if (pyType instanceof PyClassType) {
          myBody.addItem(": ").
            addWith(new LinkWrapper(PythonDocumentationProvider.LINK_TYPE_PARAM),
                    $(pyType.getName()));
        }
        else {
          myBody.addItem(": ").addItem(type);
        }
      }

      if (desc != null) {
        myEpilog.addItem(BR).addItem(desc);
      }

      return type != null;
    }

    return false;
  }

  private static Pair<String, String> getTypeAndDescr(String docString, @NotNull PyNamedParameter followed) {
    StructuredDocString structuredDocString = DocStringUtil.parse(docString);
    String type = null;
    String desc = null;
    if (structuredDocString != null) {
      final String name = followed.getName();
      type = structuredDocString.getParamType(name);

      desc = structuredDocString.getParamDescription(name);
    }

    return Pair.create(type, desc);
  }

  private void buildFromAttributeDoc() {
    PyClass cls = PsiTreeUtil.getParentOfType(myElement, PyClass.class);
    assert cls != null;
    String type = PyUtil.isInstanceAttribute((PyExpression)myElement) ? "Instance attribute " : "Class attribute ";
    myProlog
      .addItem(type).addWith(TagBold, $().addWith(TagCode, $(((PyTargetExpression)myElement).getName())))
      .addItem(" of class ").addWith(PythonDocumentationProvider.LinkMyClass, $().addWith(TagCode, $(cls.getName()))).addItem(BR)
    ;

    final String docString = ((PyTargetExpression)myElement).getDocStringValue();
    if (docString != null) {
      addFormattedDocString(myElement, docString, myBody, myEpilog);
    }
  }

  public static String[] removeCommonIndentation(String docstring) {
    // detect common indentation
    String[] lines = LineTokenizer.tokenize(docstring, false);
    boolean isFirst = true;
    int cutWidth = Integer.MAX_VALUE;
    int firstIndentedLine = 0;
    for (String frag : lines) {
      if (frag.length() == 0) continue;
      int padWidth = 0;
      final Matcher matcher = ourSpacesPattern.matcher(frag);
      if (matcher.find()) {
        padWidth = matcher.end();
      }
      if (isFirst) {
        isFirst = false;
        if (padWidth == 0) {    // first line may have zero padding
          firstIndentedLine = 1;
          continue;
        }
      }
      if (padWidth < cutWidth) cutWidth = padWidth;
    }
    // remove common indentation
    if (cutWidth > 0 && cutWidth < Integer.MAX_VALUE) {
      for (int i = firstIndentedLine; i < lines.length; i += 1) {
        if (lines[i].length() >= cutWidth)
          lines[i] = lines[i].substring(cutWidth);
      }
    }
    List<String> result = new ArrayList<String>();
    for (String line : lines) {
      if (line.startsWith(PyConsoleUtil.ORDINARY_PROMPT)) break;
      result.add(line);
    }
    return result.toArray(new String[result.size()]);
  }

  private void addModulePath(PyFile followed) {
    // what to prepend to a module description?
    final VirtualFile file = followed.getVirtualFile();
    if (file == null) {
      myProlog.addWith(TagSmall, $(PyBundle.message("QDOC.module.path.unknown")));
    }
    else {
      final String path = file.getPath();
      RootFinder finder = new RootFinder(path);
      RootVisitorHost.visitRoots(followed, finder);
      final String rootPath = finder.getResult();
      if (rootPath != null) {
        String afterPart = path.substring(rootPath.length());
        myProlog.addWith(TagSmall, $(rootPath).addWith(TagBold, $(afterPart)));
      }
      else {
        myProlog.addWith(TagSmall, $(path));
      }
    }
  }

  private static class RootFinder implements RootVisitor {
    private String myResult;
    private String myPath;

    private RootFinder(String path) {
      myPath = path;
    }

    public boolean visitRoot(VirtualFile root, Module module, Sdk sdk, boolean isModuleSource) {
      String vpath = VfsUtilCore.urlToPath(root.getUrl());
      if (myPath.startsWith(vpath)) {
        myResult = vpath;
        return false;
      }
      else {
        return true;
      }
    }

    String getResult() {
      return myResult;
    }
  }
}
