/*
 * Copyright (c) 2005 Your Corporation. All Rights Reserved.
 */
package com.intellij.util.xml;

import com.intellij.psi.xml.XmlAttribute;

/**
 * @author peter
 */
public interface DomAttributeValue<T> extends GenericValue<T>{
  XmlAttribute getXmlAttribute();
}
