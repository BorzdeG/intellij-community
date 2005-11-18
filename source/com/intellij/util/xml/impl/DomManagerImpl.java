/*
 * Copyright (c) 2005 Your Corporation. All Rights Reserved.
 */
package com.intellij.util.xml.impl;

import com.intellij.openapi.components.ProjectComponent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.pom.PomModel;
import com.intellij.pom.PomModelAspect;
import com.intellij.pom.event.PomModelEvent;
import com.intellij.pom.event.PomModelListener;
import com.intellij.pom.xml.XmlAspect;
import com.intellij.pom.xml.XmlChangeSet;
import com.intellij.psi.PsiLock;
import com.intellij.psi.xml.XmlElement;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.util.xml.*;
import com.intellij.util.xml.events.DomEvent;
import net.sf.cglib.core.CodeGenerationException;
import net.sf.cglib.proxy.InvocationHandler;
import net.sf.cglib.proxy.Proxy;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author peter
 */
public class DomManagerImpl extends DomManager implements ProjectComponent {
  private static final Key<DomNameStrategy> NAME_STRATEGY_KEY = Key.create("NameStrategy");
  private static final Key<DomInvocationHandler> CACHED_HANDLER = Key.create("CachedInvocationHandler");
  private static final Key<DomFileElementImpl> CACHED_FILE_ELEMENT = Key.create("CachedFileElement");

  private final List<DomEventListener> myListeners = new ArrayList<DomEventListener>();
  private final ConverterManagerImpl myConverterManager = new ConverterManagerImpl();
  private final Map<Class<? extends DomElement>, Class> myClass2ProxyClass = new HashMap<Class<? extends DomElement>, Class>();
  private final Map<Type, MethodsMap> myMethodsMaps = new HashMap<Type, MethodsMap>();
  private final Map<Type, InvocationCache> myInvocationCaches = new HashMap<Type, InvocationCache>();
  private final Map<Class<? extends DomElement>, ClassChooser> myClassChoosers = new HashMap<Class<? extends DomElement>, ClassChooser>();
  private DomEventListener[] myCachedListeners;
  private PomModelListener myXmlListener;
  private Project myProject;
  private PomModel myPomModel;
  private boolean myChanging;


  public DomManagerImpl(final PomModel pomModel, final Project project) {
    myPomModel = pomModel;
    myProject = project;
  }

  public final void addDomEventListener(DomEventListener listener) {
    myCachedListeners = null;
    myListeners.add(listener);
  }

  public final void removeDomEventListener(DomEventListener listener) {
    myCachedListeners = null;
    myListeners.remove(listener);
  }

  public final ConverterManagerImpl getConverterManager() {
    return myConverterManager;
  }

  protected final void fireEvent(DomEvent event) {
    DomEventListener[] listeners = myCachedListeners;
    if (listeners == null) {
      listeners = myCachedListeners = myListeners.toArray(new DomEventListener[myListeners.size()]);
    }
    for (DomEventListener listener : listeners) {
      listener.eventOccured(event);
    }
  }

  final MethodsMap getMethodsMap(final Type type) {
    MethodsMap methodsMap = myMethodsMaps.get(type);
    if (methodsMap == null) {
      if (type instanceof Class) {
        methodsMap = new MethodsMap((Class<? extends DomElement>)type);
        myMethodsMaps.put(type, methodsMap);
      }
      else if (type instanceof ParameterizedType) {
        ParameterizedType parameterizedType = (ParameterizedType)type;
        methodsMap = new MethodsMap((Class<? extends DomElement>)parameterizedType.getRawType());
        myMethodsMaps.put(type, methodsMap);
      }
      else {
        assert false : "Type not supported " + type;
      }
    }
    return methodsMap;
  }

  final InvocationCache getInvocationCache(final Type type) {
    InvocationCache invocationCache = myInvocationCaches.get(type);
    if (invocationCache == null) {
      invocationCache = new InvocationCache();
      myInvocationCaches.put(type, invocationCache);
    }
    return invocationCache;
  }

  private Class getConcreteType(Class aClass, XmlTag tag) {
    final ClassChooser classChooser = myClassChoosers.get(aClass);
    return classChooser == null ? aClass : classChooser.chooseClass(tag);
  }

  final DomElement createDomElement(final DomInvocationHandler handler) {
    synchronized (PsiLock.LOCK) {
      try {
        XmlTag tag = handler.getXmlTag();
        Class clazz = getProxyClassFor(getConcreteType(DomUtil.getRawType(handler.getDomElementType()), tag));
        final DomElement element = (DomElement)clazz.getConstructor(InvocationHandler.class).newInstance(handler);
        handler.setProxy(element);
        handler.attach(tag);
        return element;
      }
      catch (RuntimeException e) {
        throw e;
      }
      catch (Exception e) {
        throw new CodeGenerationException(e);
      }
    }
  }

  private Class getProxyClassFor(final Class<? extends DomElement> aClass) {
    Class proxyClass = myClass2ProxyClass.get(aClass);
    if (proxyClass == null) {
      proxyClass = Proxy.getProxyClass(null, new Class[]{aClass, DomProxy.class});
      myClass2ProxyClass.put(aClass, proxyClass);
    }
    return proxyClass;
  }

  public Project getProject() {
    return myProject;
  }

  public final void setNameStrategy(final XmlFile file, final DomNameStrategy strategy) {
    file.putUserData(NAME_STRATEGY_KEY, strategy);
  }

  @NotNull
  public final <T extends DomElement> DomFileElementImpl<T> getFileElement(final XmlFile file,
                                                                           final Class<T> aClass,
                                                                           String rootTagName) {
    synchronized (PsiLock.LOCK) {
      DomFileElementImpl<T> element = getCachedElement(file);
      if (element == null) {
        element = new DomFileElementImpl<T>(file, aClass, rootTagName, this);
        setCachedElement(file, element);
      }
      return element;
    }
  }

  protected static void setCachedElement(final XmlFile file, final DomFileElementImpl element) {
    file.putUserData(CACHED_FILE_ELEMENT, element);
  }

  protected static void setCachedElement(final XmlTag tag, final DomInvocationHandler element) {
    if (tag != null) {
      tag.putUserData(CACHED_HANDLER, element);
    }
  }

  @Nullable
  public static DomFileElementImpl getCachedElement(final XmlFile file) {
    return file.getUserData(CACHED_FILE_ELEMENT);
  }

  @Nullable
  public static DomInvocationHandler getCachedElement(final XmlElement xmlElement) {
    return xmlElement.getUserData(CACHED_HANDLER);
  }

  @NonNls
  public final String getComponentName() {
    return getClass().getName();
  }

  public final synchronized boolean setChanging(final boolean changing) {
    boolean oldChanging = myChanging;
    myChanging = changing;
    return oldChanging;
  }

  public final boolean isChanging() {
    return myChanging;
  }

  public final void initComponent() {
  }

  public final void disposeComponent() {
  }

  public final <T extends DomElement> void registerClassChooser(final Class<T> aClass, final ClassChooser<T> classChooser) {
    myClassChoosers.put(aClass, classChooser);
  }

  public final <T extends DomElement> void unregisterClassChooser(Class<T> aClass) {
    myClassChoosers.remove(aClass);
  }

  public final void projectOpened() {
    final XmlAspect xmlAspect = myPomModel.getModelAspect(XmlAspect.class);
    assert xmlAspect != null;
    myXmlListener = new PomModelListener() {
      public void modelChanged(PomModelEvent event) {
        if (myChanging) return;
        final XmlChangeSet changeSet = (XmlChangeSet)event.getChangeSet(xmlAspect);
        if (changeSet != null) {
          new ExternalChangeProcessor(changeSet).processChanges();
        }
      }

      public boolean isAspectChangeInteresting(PomModelAspect aspect) {
        return xmlAspect.equals(aspect);
      }
    };
    myPomModel.addModelListener(myXmlListener);
  }

  public final void projectClosed() {
    myPomModel.removeModelListener(myXmlListener);
  }

}