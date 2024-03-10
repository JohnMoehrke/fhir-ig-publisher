package org.hl7.fhir.igtools.publisher;

/*-
 * #%L
 * org.hl7.fhir.publisher.core
 * %%
 * Copyright (C) 2014 - 2019 Health Level 7
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */


import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.hl7.fhir.exceptions.FHIRException;
import org.hl7.fhir.r5.context.IWorkerContext;
import org.hl7.fhir.r5.elementmodel.Element;
import org.hl7.fhir.r5.elementmodel.Manager;
import org.hl7.fhir.r5.elementmodel.Manager.FhirFormat;
import org.hl7.fhir.r5.elementmodel.ObjectConverter;
import org.hl7.fhir.r5.model.CanonicalResource;
import org.hl7.fhir.r5.model.CodeSystem;
import org.hl7.fhir.r5.model.ElementDefinition;
import org.hl7.fhir.r5.model.NamingSystem;
import org.hl7.fhir.r5.model.NamingSystem.NamingSystemIdentifierType;
import org.hl7.fhir.r5.model.NamingSystem.NamingSystemUniqueIdComponent;
import org.hl7.fhir.r5.model.OperationDefinition;
import org.hl7.fhir.r5.model.Questionnaire;
import org.hl7.fhir.r5.model.Resource;
import org.hl7.fhir.r5.model.StructureDefinition;
import org.hl7.fhir.r5.model.StructureMap;
import org.hl7.fhir.r5.model.ValueSet;
import org.hl7.fhir.r5.terminologies.ImplicitValueSets;
import org.hl7.fhir.r5.utils.validation.IResourceValidator;
import org.hl7.fhir.r5.utils.validation.IValidationPolicyAdvisor;
import org.hl7.fhir.r5.utils.validation.IValidatorResourceFetcher;
import org.hl7.fhir.r5.utils.validation.IValidationPolicyAdvisor.AdditionalBindingPurpose;
import org.hl7.fhir.r5.utils.validation.IValidationPolicyAdvisor.CodedContentValidationAction;
import org.hl7.fhir.r5.utils.validation.IValidationPolicyAdvisor.ElementValidationAction;
import org.hl7.fhir.r5.utils.validation.IValidationPolicyAdvisor.ResourceValidationAction;
import org.hl7.fhir.r5.utils.validation.constants.BindingKind;
import org.hl7.fhir.r5.utils.validation.constants.CodedContentValidationPolicy;
import org.hl7.fhir.r5.utils.validation.constants.ContainedReferenceValidationPolicy;
import org.hl7.fhir.r5.utils.validation.constants.ReferenceValidationPolicy;
import org.hl7.fhir.utilities.SIDUtilities;
import org.hl7.fhir.utilities.TextFile;
import org.hl7.fhir.utilities.Utilities;
import org.hl7.fhir.utilities.VersionUtilities;
import org.hl7.fhir.utilities.npm.NpmPackage;

public class ValidationServices implements IValidatorResourceFetcher, IValidationPolicyAdvisor {

  private IWorkerContext context;
  private IGKnowledgeProvider ipg;
  private List<FetchedFile> files;
  private List<NpmPackage> packages;
  private List<String> otherUrls = new ArrayList<>();
  private List<String> mappingUrls = new ArrayList<>();
  private boolean bundleReferencesResolve;
  private List<SpecMapManager> specMaps;
  
  
  public ValidationServices(IWorkerContext context, IGKnowledgeProvider ipg, List<FetchedFile> files, List<NpmPackage> packages, boolean bundleReferencesResolve, List<SpecMapManager> specMaps) {
    super();
    this.context = context;
    this.ipg = ipg;
    this.files = files;
    this.packages = packages;
    this.bundleReferencesResolve = bundleReferencesResolve;
    this.specMaps = specMaps;
    initOtherUrls();
  }

  @Override
  public Element fetch(IResourceValidator validator, Object appContext, String url) throws FHIRException, IOException {
    if (url == null)
      return null;
    String turl = (!Utilities.isAbsoluteUrl(url)) ? Utilities.pathURL(ipg.getCanonical(), url) : url;
    Resource res = context.fetchResource(getResourceType(turl), turl);
    if (res != null) {
      Element e = (Element)res.getUserData("element");
      if (e!=null)
        return e;
      else
        return new ObjectConverter(context).convert(res);
    }

    ValueSet vs = ImplicitValueSets.build(url);
    if (vs != null)
      return new ObjectConverter(context).convert(vs);
    
    for (NpmPackage npm : packages) {
      if (Utilities.isAbsoluteUrl(url) && npm.canonical() != null && url.startsWith(npm.canonical())) {
        String u = url.substring(npm.canonical().length());
        if (u.startsWith("/"))
          u = u.substring(1);
        String[] ul = u.split("\\/");
        if (ul.length >= 2) {
          InputStream s = npm.loadResource(ul[0], ul[1]);
          if (s == null) {
            s = npm.loadExampleResource(ul[0], ul[1]);
          }
          if (s != null)
            return Manager.makeParser(context, FhirFormat.JSON).parseSingle(s, null);
        }
      }
    }
    String[] parts = url.split("\\/");
    
    if (appContext != null) {
      Element bnd = (Element) appContext;
      int count = 0;
      for (Element be : bnd.getChildren("entry")) {
        count++;
        Element ber = be.getNamedChild("resource");
        if (ber != null) {
          if (be.hasChild("fullUrl") && be.getChildByName("fullUrl").equals(url)) {
            return ber;
          }
          if (parts.length == 2 && ber.fhirType().equals(parts[0]) && ber.hasChild("id") && ber.getChildValue("id").equals(parts[1])) 
            return ber;
        }        
      }
    }
    
    if (!Utilities.isAbsoluteUrl(url) || url.startsWith(ipg.getCanonical())) {
      if (parts.length == 2) {
        for (FetchedFile f : files) {
          for (FetchedResource r : f.getResources()) {
            if (r.getElement().fhirType().equals(parts[parts.length-2]) && r.getId().equals(parts[parts.length-1]))
              return r.getElement();
          }
        }
      }
    }
    
    if (Utilities.isAbsoluteUrl(url)) {
      for (FetchedFile f : files) {
        for (FetchedResource r : f.getResources()) {
          if (r.getElement().fhirType().equals("Bundle")) {
            for (Element be : r.getElement().getChildren("entry")) {
              Element ber = be.getNamedChild("resource");
              if (ber != null) {
                if (be.hasChild("fullUrl") && be.getChildValue("fullUrl").equals(url))
                  return ber;
              }
            }
          }
        }
      }
    }

    if (parts.length >= 2 && Utilities.existsInList(parts[parts.length - 2], context.getResourceNames())) {
      for (int i = packages.size() - 1; i >= 0; i--) {
        NpmPackage npm = packages.get(i);
        InputStream s = npm.loadExampleResource(parts[parts.length - 2], parts[parts.length - 1]);
        if (s != null) {
            return Manager.makeParser(context, FhirFormat.JSON).parseSingle(s, null);
        }
      }
    }
    return null;
  }

  private Class getResourceType(String url) {
    if (url.contains("/ValueSet/"))
      return ValueSet.class;
    if (url.contains("/StructureDefinition/"))
      return StructureDefinition.class;
    if (url.contains("/CodeSystem/"))
      return CodeSystem.class;
    if (url.contains("/OperationDefinition/"))
      return OperationDefinition.class;
    if (url.contains("/Questionnaire/"))
      return Questionnaire.class;
    return null;
  }

  @Override
  public ContainedReferenceValidationPolicy policyForContained(IResourceValidator validator,
      Object appContext,
      StructureDefinition structure,
      ElementDefinition element,
      String containerType,
      String containerId,
      Element.SpecialElement containingResourceType,
      String path,
      String url) {
    return ContainedReferenceValidationPolicy.CHECK_VALID;
  }

  @Override
  public ReferenceValidationPolicy policyForReference(IResourceValidator validator, Object appContext, String path, String url) {
    if (path.startsWith("Bundle.") && !bundleReferencesResolve) {
      return ReferenceValidationPolicy.CHECK_TYPE_IF_EXISTS;
    } else {
      return ReferenceValidationPolicy.CHECK_EXISTS_AND_TYPE;
    }
  }

  @Override
  public boolean resolveURL(IResourceValidator validator, Object appContext, String path, String url, String type, boolean canonical) throws IOException {
    String u = url;
    String v = null;
    if (url.contains("|")) {
      u = url.substring(0, url.indexOf("|"));
      v = url.substring(url.indexOf("|")+1);
    }
    if (otherUrls.contains(u) || otherUrls.contains(url)) {
      // ignore the version
      return true;
    }

    if (SIDUtilities.isKnownSID(u)) {
      return (v == null) || !SIDUtilities.isInvalidVersion(u, v);
    }

    if (u.startsWith("http://hl7.org/fhirpath/System.")) {
      return (v == null || Utilities.existsInList(v, "2.0.0", "1.3.0", "1.2.0", "1.1.0", "1.0.0", "0.3.0", "0.2.0"));       
    }
    
    if (path.contains("StructureDefinition.mapping") && (mappingUrls.contains(u) || mappingUrls.contains(url))) {
      // ignore the version
      return true;
    }
    
    if (url.contains("*")) {
      // for now, this is only done for StructureMap
      for (StructureMap map : context.fetchResourcesByType(StructureMap.class)) {
        if (urlMatches(url, map.getUrl())) {
          return true;
        }
      }
    }

    if (url.startsWith(ipg.getCanonical())) {
      for (FetchedFile f : files) {
        for (FetchedResource r: f.getResources()) {
          if (Utilities.pathURL(ipg.getCanonical(), r.fhirType(), r.getId()).equals(url) && (!canonical || VersionUtilities.getCanonicalResourceNames(context.getVersion()).contains(r.fhirType()))) {
            return true;
          }
        }
      }
    }
    for (NamingSystem ns : context.fetchResourcesByType(NamingSystem.class)) {
      if (hasURL(ns, u)) {
        // ignore the version?
        return true;
      }
    }
    
    for (SpecMapManager sp : specMaps) {
      String base = url.contains("#") ? url.substring(0, url.indexOf("#")) : url;
      if (sp.hasTarget(base)) {
        return true;
      }
    }
    if (u.startsWith("http://hl7.org/fhir")) {
      if (org.hl7.fhir.r5.utils.BuildExtensions.allConsts().contains(u)) {
        return true;
      }
      try {
        return context.fetchResourceWithException(Resource.class, url) != null;
      } catch (FHIRException e) {
        return false;
      }
    // todo: what to do here?
    }
    return true;
  }
  

  private boolean urlMatches(String mask, String url) {
    return url.length() > mask.length() && url.startsWith(mask.substring(0, mask.indexOf("*"))) && url.endsWith(mask.substring(mask.indexOf("*") + 1));
  }

  
  private boolean hasURL(NamingSystem ns, String url) {
    for (NamingSystemUniqueIdComponent uid : ns.getUniqueId()) {
      if (uid.getType() == NamingSystemIdentifierType.URI && uid.hasValue() && uid.getValue().equals(url)) {
        return true;
      }
    }
    return false;
  }


  public List<String> getOtherUrls() {
    return otherUrls;
  }

  public List<String> getMappingUrls() {
    return mappingUrls;
  }

  public void initOtherUrls() {
    otherUrls.clear();
    otherUrls.addAll(SIDUtilities.allSystemsList());
    otherUrls.add("http://hl7.org/fhir/w5");
    otherUrls.add("http://hl7.org/fhir/fivews");
    otherUrls.add("http://hl7.org/fhir/workflow");
    otherUrls.add("http://hl7.org/fhir/tools/StructureDefinition/resource-information");
    otherUrls.add("http://hl7.org/fhir/ConsentPolicy/opt-out"); 
    otherUrls.add("http://hl7.org/fhir/ConsentPolicy/opt-in");
  }

  @Override
  public IValidatorResourceFetcher setLocale(Locale locale) {
    return this;
  }

  @Override
  public byte[] fetchRaw(IResourceValidator validator, String source) throws MalformedURLException, IOException {
    URL url = new URL(source);
    URLConnection c = url.openConnection();
    return TextFile.streamToBytes(c.getInputStream());
  }

  @Override
  public CanonicalResource fetchCanonicalResource(IResourceValidator validator, Object appContext, String url) {
    return null;
  }

  @Override
  public boolean fetchesCanonicalResource(IResourceValidator validator, String url) {
    return false;
  }

  @Override
  public EnumSet<CodedContentValidationAction> policyForCodedContent(IResourceValidator validator,
      Object appContext,
      String stackPath,
      ElementDefinition definition,
      StructureDefinition structure,
      BindingKind kind,
      AdditionalBindingPurpose purpose,
      ValueSet valueSet,
      List<String> systems) {
    if (VersionUtilities.isR4BVer(context.getVersion()) && 
        "ImplementationGuide.definition.parameter.code".equals(definition.getBase().getPath())) {
      return EnumSet.noneOf(CodedContentValidationAction.class);
    }
    return EnumSet.allOf(CodedContentValidationAction.class);
  }

  @Override
  public EnumSet<ResourceValidationAction> policyForResource(IResourceValidator validator, Object appContext,
      StructureDefinition type, String path) {
    return EnumSet.allOf(ResourceValidationAction.class);
  }

  @Override
  public EnumSet<ElementValidationAction> policyForElement(IResourceValidator validator, Object appContext,
      StructureDefinition structure, ElementDefinition element, String path) {
    return EnumSet.allOf(ElementValidationAction.class);
  }

  @Override
  public Set<String> fetchCanonicalResourceVersions(IResourceValidator validator, Object appContext, String url) {
    Set<String> res = new HashSet<>();
    for (Resource r : context.fetchResourcesByUrl(Resource.class, url)) {
      if (r instanceof CanonicalResource) {
        
        CanonicalResource cr = (CanonicalResource) r;
        res.add(cr.hasVersion() ? cr.getVersion() : "{{unversioned}}");
      }
    }
    return res;
  }
}
