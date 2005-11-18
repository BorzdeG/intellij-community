/*
 * Copyright (c) 2005 Your Corporation. All Rights Reserved.
 */
package com.intellij.util.xml;

import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.xml.XmlTag;
import com.intellij.util.xml.reflect.DomGenericInfo;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Type;

/**
 * @author peter
 */
public interface DomElement {

  @Nullable
  XmlTag getXmlTag();

  DomFileElement<?> getRoot();

  DomElement getParent();

  XmlTag ensureTagExists();

  void undefine();

  boolean isValid();

  DomGenericInfo getMethodsInfo();

  String getXmlElementName();

  void acceptChildren(DomElementVisitor visitor);

  DomManager getManager();

  Type getDomElementType();

  DomNameStrategy getNameStrategy();

  String getCommonPresentableName();

  GlobalSearchScope getResolveScope();
}
