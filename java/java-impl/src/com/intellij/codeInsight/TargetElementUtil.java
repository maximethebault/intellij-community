/*
 * Copyright 2000-2015 JetBrains s.r.o.
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
package com.intellij.codeInsight;

import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.util.Computable;
import com.intellij.psi.*;
import com.intellij.psi.search.LocalSearchScope;
import com.intellij.psi.search.SearchScope;
import com.intellij.psi.search.searches.ClassInheritorsSearch;
import com.intellij.psi.util.*;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlTag;
import com.intellij.psi.xml.XmlText;
import com.intellij.util.Processor;
import com.intellij.util.ThreeState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public class TargetElementUtil extends TargetElementEvaluatorEx2 implements TargetElementUtilExtender{
  public static final int NEW_AS_CONSTRUCTOR = 0x04;
  public static final int THIS_ACCEPTED = 0x10;
  public static final int SUPER_ACCEPTED = 0x20;

  @Override
  public int getAdditionalAccepted() {
    return NEW_AS_CONSTRUCTOR | THIS_ACCEPTED | SUPER_ACCEPTED;
  }

  @Override
  public int getAdditionalDefinitionSearchFlags() {
    return THIS_ACCEPTED | SUPER_ACCEPTED;
  }

  @Override
  public int getAdditionalReferenceSearchFlags() {
    return NEW_AS_CONSTRUCTOR;
  }

  @Nullable
  @Override
  public PsiElement adjustTargetElement(Editor editor, int offset, int flags, @NotNull PsiElement targetElement) {
    if (targetElement instanceof PsiKeyword) {
      if (targetElement.getParent() instanceof PsiThisExpression) {
        if ((flags & THIS_ACCEPTED) == 0) return null;
        PsiType type = ((PsiThisExpression)targetElement.getParent()).getType();
        if (!(type instanceof PsiClassType)) return null;
        return ((PsiClassType)type).resolve();
      }

      if (targetElement.getParent() instanceof PsiSuperExpression) {
        if ((flags & SUPER_ACCEPTED) == 0) return null;
        PsiType type = ((PsiSuperExpression)targetElement.getParent()).getType();
        if (!(type instanceof PsiClassType)) return null;
        return ((PsiClassType)type).resolve();
      }
    }
    return super.adjustTargetElement(editor, offset, flags, targetElement);
  }

  @Override
  @NotNull
  public ThreeState isAcceptableReferencedElement(@NotNull final PsiElement element, final PsiElement referenceOrReferencedElement) {
    if (isEnumConstantReference(element, referenceOrReferencedElement)) return ThreeState.NO;
    return super.isAcceptableReferencedElement(element, referenceOrReferencedElement);
  }

  private static boolean isEnumConstantReference(final PsiElement element, final PsiElement referenceOrReferencedElement) {
    return element != null &&
           element.getParent() instanceof PsiEnumConstant &&
           referenceOrReferencedElement instanceof PsiMethod &&
           ((PsiMethod)referenceOrReferencedElement).isConstructor();
  }

  @Nullable
  @Override
  public PsiElement getElementByReference(@NotNull PsiReference ref, int flags) {
    return null;
  }

  @Nullable
  @Override
  public PsiElement adjustReferenceOrReferencedElement(PsiFile file,
                                                       Editor editor,
                                                       int offset,
                                                       int flags,
                                                       @Nullable PsiElement refElement) {
    PsiReference ref = null;
    if (refElement == null) {
      ref = TargetElementUtilBase.findReference(editor, offset);
      if (ref instanceof PsiJavaReference) {
        refElement = ((PsiJavaReference)ref).advancedResolve(true).getElement();
      }
      else if (ref == null) {
        final PsiElement element = file.findElementAt(offset);
        if (element != null) {
          final PsiElement parent = element.getParent();
          if (parent instanceof PsiFunctionalExpression) {
            refElement = PsiUtil.resolveClassInType(((PsiFunctionalExpression)parent).getFunctionalInterfaceType());
          }
        } 
      }
    }

    if (refElement != null) {
      if ((flags & NEW_AS_CONSTRUCTOR) != 0) {
        if (ref == null) {
          ref = TargetElementUtilBase.findReference(editor, offset);
        }
        if (ref != null) {
          PsiElement parent = ref.getElement().getParent();
          if (parent instanceof PsiAnonymousClass) {
            parent = parent.getParent();
          }
          if (parent instanceof PsiNewExpression) {
            PsiMethod constructor = ((PsiNewExpression)parent).resolveConstructor();
            if (constructor != null) {
              refElement = constructor;
            } else if (refElement instanceof PsiClass && ((PsiClass)refElement).getConstructors().length > 0) {
              return null;
            }
          }
        }
      }

      if (refElement instanceof PsiMirrorElement) {
        return ((PsiMirrorElement)refElement).getPrototype();
      }

      if (refElement instanceof PsiClass) {
        final PsiFile containingFile = refElement.getContainingFile();
        if (containingFile != null && containingFile.getVirtualFile() == null) { // in mirror file of compiled class
          String qualifiedName = ((PsiClass)refElement).getQualifiedName();
          if (qualifiedName == null) return null;
          return JavaPsiFacade.getInstance(refElement.getProject()).findClass(qualifiedName, refElement.getResolveScope());
        }
      }
    }
    return super.adjustReferenceOrReferencedElement(file, editor, offset, flags, refElement);
  }


  @Nullable
  @Override
  public PsiElement getNamedElement(@NotNull final PsiElement element) {
    PsiElement parent = element.getParent();
    if (element instanceof PsiIdentifier) {
      if (parent instanceof PsiClass && element.equals(((PsiClass)parent).getNameIdentifier())
        || parent instanceof PsiVariable && element.equals(((PsiVariable)parent).getNameIdentifier())
        || parent instanceof PsiMethod && element.equals(((PsiMethod)parent).getNameIdentifier())
        || parent instanceof PsiLabeledStatement && element.equals(((PsiLabeledStatement)parent).getLabelIdentifier())) {
        return parent;
      }
    }
    return null;
  }

  public boolean isAcceptableNamedParent(@NotNull PsiElement parent) {
    return !(parent instanceof XmlAttribute)
        && !(parent instanceof PsiFile && InjectedLanguageManager.getInstance(parent.getProject()).isInjectedFragment((PsiFile)parent));
  }

  @Nullable
  public static PsiReferenceExpression findReferenceExpression(Editor editor) {
    final PsiReference ref = TargetElementUtilBase.findReference(editor);
    return ref instanceof PsiReferenceExpression ? (PsiReferenceExpression)ref : null;
  }

  @Nullable
  @Override
  public PsiElement adjustReference(@NotNull final PsiReference ref) {
    final PsiElement parent = ref.getElement().getParent();
    if (parent instanceof PsiMethodCallExpression) return parent;
    return super.adjustReference(ref);
  }

  @Nullable
  @Override
  public PsiElement adjustElement(Editor editor, int flags, @Nullable PsiElement element, @Nullable PsiElement contextElement) {
    if (element != null) {
      if (element instanceof PsiAnonymousClass) {
        return ((PsiAnonymousClass)element).getBaseClassType().resolve();
      }
      return element;
    }
    if (contextElement == null) return null;
    final PsiElement parent = contextElement.getParent();
    if (parent instanceof XmlText || parent instanceof XmlAttributeValue) {
      final PsiElement gParent = parent.getParent();
      if (gParent == null) return null;
      return TargetElementUtilBase.getInstance().findTargetElement(editor, flags, gParent.getTextRange().getStartOffset() + 1);
    }
    else if (parent instanceof XmlTag || parent instanceof XmlAttribute) {
      return TargetElementUtilBase.getInstance().findTargetElement(editor, flags, parent.getTextRange().getStartOffset() + 1);
    }
    return null;
  }

  @Override
  @Nullable
  public Collection<PsiElement> getTargetCandidates(@NotNull PsiReference reference) {
    PsiElement parent = reference.getElement().getParent();
    if (parent instanceof PsiMethodCallExpression || parent instanceof PsiNewExpression && 
                                                     ((PsiNewExpression)parent).getArrayDimensions().length == 0 &&
                                                     ((PsiNewExpression)parent).getArrayInitializer() == null) {
      PsiCallExpression callExpr = (PsiCallExpression)parent;
      boolean allowStatics = false;
      PsiExpression qualifier = callExpr instanceof PsiMethodCallExpression ? ((PsiMethodCallExpression)callExpr).getMethodExpression().getQualifierExpression()
                                                                            : callExpr instanceof PsiNewExpression ? ((PsiNewExpression)callExpr).getQualifier() : null;
      if (qualifier == null) {
        allowStatics = true;
      }
      else if (qualifier instanceof PsiJavaCodeReferenceElement) {
        PsiElement referee = ((PsiJavaCodeReferenceElement)qualifier).advancedResolve(true).getElement();
        if (referee instanceof PsiClass) allowStatics = true;
      }
      PsiResolveHelper helper = JavaPsiFacade.getInstance(parent.getProject()).getResolveHelper();
      PsiElement[] candidates = PsiUtil.mapElements(helper.getReferencedMethodCandidates(callExpr, false));
      final Collection<PsiElement> methods = new LinkedHashSet<PsiElement>();
      for (PsiElement candidate1 : candidates) {
        PsiMethod candidate = (PsiMethod)candidate1;
        if (candidate.hasModifierProperty(PsiModifier.STATIC) && !allowStatics) continue;
        List<PsiMethod> supers = Arrays.asList(candidate.findSuperMethods());
        if (supers.isEmpty()) {
          methods.add(candidate);
        }
        else {
          methods.addAll(supers);
        }
      }
      return methods;
    }

    return super.getTargetCandidates(reference);
  }

  @Override
  @Nullable
  public PsiElement getGotoDeclarationTarget(@NotNull final PsiElement element, @Nullable final PsiElement navElement) {
    if (navElement == element && element instanceof PsiCompiledElement && element instanceof PsiMethod) {
      PsiMethod method = (PsiMethod)element;
      if (method.isConstructor() && method.getParameterList().getParametersCount() == 0) {
        PsiClass aClass = method.getContainingClass();
        PsiElement navClass = aClass == null ? null : aClass.getNavigationElement();
        if (aClass != navClass) return navClass;
      }
    }
    return super.getGotoDeclarationTarget(element, navElement);
  }

  @Override
  public boolean includeSelfInGotoImplementation(@NotNull final PsiElement element) {
    if (element instanceof PsiModifierListOwner && ((PsiModifierListOwner)element).hasModifierProperty(PsiModifier.ABSTRACT)) {
      return false;
    }
    return super.includeSelfInGotoImplementation(element);
  }

  @Override
  public boolean acceptImplementationForReference(@Nullable PsiReference reference, @NotNull PsiElement element) {
    if (reference instanceof PsiReferenceExpression && element instanceof PsiMember) {
      return getMemberClass(reference, element) != null;
    }
    return super.acceptImplementationForReference(reference, element);
  }

  private static PsiClass[] getMemberClass(final PsiReference reference, final PsiElement element) {
    return ApplicationManager.getApplication().runReadAction(new Computable<PsiClass[]>() {
      @Override
      public PsiClass[] compute() {
        PsiClass containingClass = ((PsiMember)element).getContainingClass();
        final PsiExpression expression = ((PsiReferenceExpression)reference).getQualifierExpression();
        PsiClass psiClass;
        if (expression != null) {
          psiClass = PsiUtil.resolveClassInType(expression.getType());
        } else {
          if (element instanceof PsiClass) {
            psiClass = (PsiClass)element;
            final PsiElement resolve = reference.resolve();
            if (resolve instanceof PsiClass) {
              containingClass = (PsiClass)resolve;
            }
          } else {
            psiClass = PsiTreeUtil.getParentOfType((PsiReferenceExpression)reference, PsiClass.class);
          }
        }

        if (containingClass == null && psiClass == null) return PsiClass.EMPTY_ARRAY;
        if (containingClass != null) {
          PsiElementFindProcessor<PsiClass> processor1 = new PsiElementFindProcessor<PsiClass>(containingClass);
          while (psiClass != null) {
            if (!processor1.process(psiClass) ||
                !ClassInheritorsSearch.search(containingClass).forEach(new PsiElementFindProcessor<PsiClass>(psiClass)) ||
                !ClassInheritorsSearch.search(psiClass).forEach(processor1)) {
              return new PsiClass[] {psiClass};
            }
            psiClass = psiClass.getContainingClass();
          }
        }
        return null;
      }
    });
  }

  
  @Override
  @Nullable
  public SearchScope getSearchScope(Editor editor, @NotNull PsiElement element) {
    final PsiReferenceExpression referenceExpression = editor != null ? findReferenceExpression(editor) : null;
    if (referenceExpression != null && element instanceof PsiMethod) {
      final PsiClass[] memberClass = getMemberClass(referenceExpression, element);
      if (memberClass != null && memberClass.length == 1) {
        return CachedValuesManager.getCachedValue(referenceExpression, new CachedValueProvider<SearchScope>() {
          @Nullable
          @Override
          public Result<SearchScope> compute() {
            final List<PsiClass> classesToSearch = new ArrayList<PsiClass>();
            classesToSearch.addAll(ClassInheritorsSearch.search(memberClass[0], true).findAll());

            final Set<PsiClass> supers = new HashSet<PsiClass>();
            for (PsiClass psiClass : classesToSearch) {
              supers.addAll(InheritanceUtil.getSuperClasses(psiClass));
            }
            classesToSearch.addAll(supers);

            return new Result<SearchScope>(new LocalSearchScope(PsiUtilCore.toPsiElementArray(classesToSearch)), PsiModificationTracker.JAVA_STRUCTURE_MODIFICATION_COUNT);
          }
        });
      }
    }
    return super.getSearchScope(editor, element);
  }

  private static class PsiElementFindProcessor<T extends PsiClass> implements Processor<T> {
    private final T myElement;

    public PsiElementFindProcessor(T t) {
      myElement = t;
    }

    @Override
    public boolean process(T t) {
      if (InheritanceUtil.isInheritorOrSelf(t, myElement, true)) return false;
      return !myElement.getManager().areElementsEquivalent(myElement, t);
    }
  }
}
