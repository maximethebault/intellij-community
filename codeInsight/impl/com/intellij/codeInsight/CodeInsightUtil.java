package com.intellij.codeInsight;

import com.intellij.codeInsight.hint.HintManager;
import com.intellij.lang.ASTNode;
import com.intellij.lang.StdLanguages;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.codeStyle.Indent;
import com.intellij.psi.impl.source.parsing.ChameleonTransforming;
import com.intellij.psi.impl.source.tree.*;
import com.intellij.psi.search.PsiSearchHelper;
import com.intellij.psi.statistics.StatisticsManager;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;
import com.intellij.refactoring.util.RefactoringUtil;
import com.intellij.util.text.CharArrayUtil;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 *
 */
public class CodeInsightUtil {
  private static final Logger LOG = Logger.getInstance("#com.intellij.codeInsight.CodeInsightUtil");
  @NonNls private static final String JAVA_PACKAGE_PREFIX = "java.";
  @NonNls private static final String JAVAX_PACKAGE_PREFIX = "javax.";

  public static PsiExpression findExpressionInRange(PsiFile file, int startOffset, int endOffset) {
    if(!file.getViewProvider().getRelevantLanguages().contains(StdLanguages.JAVA)) return null;
    PsiElement element1 = file.getViewProvider().findElementAt(startOffset, StdLanguages.JAVA);
    PsiElement element2 = file.getViewProvider().findElementAt(endOffset - 1, StdLanguages.JAVA);
    if (element1 instanceof PsiWhiteSpace) {
      startOffset = element1.getTextRange().getEndOffset();
    }
    if (element2 instanceof PsiWhiteSpace) {
      endOffset = element2.getTextRange().getStartOffset();
    }
    final PsiElement commonParent = PsiTreeUtil.findCommonParent(element1, element2);
    final PsiExpression expression = commonParent instanceof PsiExpression ?
                                     (PsiExpression)commonParent :
                                     PsiTreeUtil.getParentOfType(commonParent, PsiExpression.class);
    if (expression == null || expression.getTextRange().getEndOffset() != endOffset) return null;
    if (expression instanceof PsiReferenceExpression && expression.getParent() instanceof PsiMethodCallExpression) return null;
    return expression;
  }

  @NotNull public static PsiElement[] findStatementsInRange(PsiFile file, int startOffset, int endOffset) {
    if(!file.getViewProvider().getRelevantLanguages().contains(StdLanguages.JAVA)) return PsiElement.EMPTY_ARRAY;
    PsiElement element1 = file.getViewProvider().findElementAt(startOffset, StdLanguages.JAVA);
    PsiElement element2 = file.getViewProvider().findElementAt(endOffset - 1, StdLanguages.JAVA);
    if (element1 instanceof PsiWhiteSpace) {
      startOffset = element1.getTextRange().getEndOffset();
      element1 = file.findElementAt(startOffset);
    }
    if (element2 instanceof PsiWhiteSpace) {
      endOffset = element2.getTextRange().getStartOffset();
      element2 = file.findElementAt(endOffset - 1);
    }
    if (element1 == null || element2 == null) return PsiElement.EMPTY_ARRAY;

    PsiElement parent = PsiTreeUtil.findCommonParent(element1, element2);
    if (parent == null) return PsiElement.EMPTY_ARRAY;
    while (true) {
      if (parent instanceof PsiStatement) {
        parent = parent.getParent();
        break;
      }
      if (parent instanceof PsiCodeBlock) break;
      if (PsiUtil.isInJspFile(parent) && parent instanceof PsiFile) break;
      if (parent instanceof PsiCodeFragment) break;
      if (parent instanceof PsiFile) return PsiElement.EMPTY_ARRAY;
      parent = parent.getParent();
    }

    if (!parent.equals(element1)) {
      while (!parent.equals(element1.getParent())) {
        element1 = element1.getParent();
      }
    }
    if (startOffset != element1.getTextRange().getStartOffset()) return PsiElement.EMPTY_ARRAY;

    if (!parent.equals(element2)) {
      while (!parent.equals(element2.getParent())) {
        element2 = element2.getParent();
      }
    }
    if (endOffset != element2.getTextRange().getEndOffset()) return PsiElement.EMPTY_ARRAY;

    if (parent instanceof PsiCodeBlock && parent.getParent() instanceof PsiBlockStatement
        && element1 == ((PsiCodeBlock)parent).getLBrace()
        && element2 == ((PsiCodeBlock)parent).getRBrace()) {
      return new PsiElement[]{parent.getParent()};
    }

/*
    if(parent instanceof PsiCodeBlock && parent.getParent() instanceof PsiBlockStatement) {
      return new PsiElement[]{parent.getParent()};
    }
*/

    PsiElement[] children = parent.getChildren();
    ArrayList<PsiElement> array = new ArrayList<PsiElement>();
    boolean flag = false;
    for (PsiElement child : children) {
      if (child.equals(element1)) {
        flag = true;
      }
      if (flag && !(child instanceof PsiWhiteSpace)) {
        array.add(child);
      }
      if (child.equals(element2)) {
        break;
      }
    }

    for (PsiElement element : array) {
      if (!(element instanceof PsiStatement
            || element instanceof PsiWhiteSpace
            || element instanceof PsiComment)) {
        return PsiElement.EMPTY_ARRAY;
      }
    }

    return array.toArray(new PsiElement[array.size()]);
  }

  public static List<PsiElement> getElementsIntersectingRange(PsiElement root, final int startOffset, final int endOffset) {
    return getElementsInRange(root, startOffset, endOffset, true);
  }

  public static List<PsiElement> getElementsInRange(PsiElement root, final int startOffset, final int endOffset) {
    return getElementsInRange(root, startOffset, endOffset, false);
  }

  public static List<PsiElement> getElementsInRange(PsiElement root, final int startOffset, final int endOffset, boolean includeAllParents) {
    PsiElement commonParent = findCommonParent(root, startOffset, endOffset);
    if (commonParent == null) return Collections.emptyList();
    final List<PsiElement> list = new ArrayList<PsiElement>();

    final int currentOffset = commonParent.getTextRange().getStartOffset();
    final TreeElementVisitor visitor = new TreeElementVisitor() {
      int offset = currentOffset;
      public void visitLeaf(LeafElement leaf) {
        offset += leaf.getTextLength();
      }
      public void visitComposite(CompositeElement composite) {
        ChameleonTransforming.transformChildren(composite);
        for (TreeElement child = composite.getFirstChildNode(); child != null; child = child.getTreeNext()) {
          if (offset > endOffset) break;
          int start = offset;
          child.acceptTree(this);
          if (startOffset <= start && offset <= endOffset) {
            list.add(child.getPsi());
          }
        }
      }
    };
    ((TreeElement)commonParent.getNode()).acceptTree(visitor);
    list.add(commonParent);

    if (includeAllParents) {
      PsiElement parent = commonParent;
      while (parent != root) {
        parent = parent.getParent();
        list.add(parent);
      }
    }

    return Collections.unmodifiableList(list);
  }

  @Nullable public static PsiElement findCommonParent(final PsiElement root, final int startOffset, final int endOffset) {
    final ASTNode leafElementAt1 = root.getNode().findLeafElementAt(startOffset);
    if(leafElementAt1 == null) return null;
    ASTNode leafElementAt2 = root.getNode().findLeafElementAt(endOffset);
    if (leafElementAt2 == null && endOffset == root.getTextLength()) leafElementAt2 = root.getNode().findLeafElementAt(endOffset - 1);
    if(leafElementAt2 == null) return null;
    ASTNode prev = leafElementAt2.getTreePrev();
    if (prev != null && prev.getTextRange().getEndOffset() == endOffset) {
      leafElementAt2 = prev;
    }
    TreeElement commonParent = (TreeElement)TreeUtil.findCommonParent(leafElementAt1, leafElementAt2);
    LOG.assertTrue(commonParent != null);
    LOG.assertTrue(commonParent.getTextRange() != null);

    while(commonParent.getTreeParent() != null &&
          commonParent.getTextRange().equals(commonParent.getTreeParent().getTextRange())) {
      commonParent = commonParent.getTreeParent();
    }
    return commonParent.getPsi();
  }

  public static void sortIdenticalShortNameClasses(PsiClass[] classes) {
    if (classes.length <= 1) return;

    final StatisticsManager statisticsManager = StatisticsManager.getInstance();
    Comparator<PsiClass> comparator = new Comparator<PsiClass>() {
      public int compare(PsiClass aClass, PsiClass bClass) {
        int count1 = statisticsManager.getMemberUseCount(null, aClass, null);
        int count2 = statisticsManager.getMemberUseCount(null, bClass, null);
        if (count1 != count2) return count2 - count1;
        boolean inProject1 = aClass.getManager().isInProject(aClass);
        boolean inProject2 = bClass.getManager().isInProject(aClass);
        if (inProject1 != inProject2) return inProject1 ? -1 : 1;
        String qName1 = aClass.getQualifiedName();
        boolean isJdk1 = qName1 != null && (qName1.startsWith(JAVA_PACKAGE_PREFIX) || qName1.startsWith(JAVAX_PACKAGE_PREFIX));
        String qName2 = bClass.getQualifiedName();
        boolean isJdk2 = qName2 != null && (qName2.startsWith(JAVA_PACKAGE_PREFIX) || qName2.startsWith(JAVAX_PACKAGE_PREFIX));
        if (isJdk1 != isJdk2) return isJdk1 ? -1 : 1;
        return 0;
      }
    };
    Arrays.sort(classes, comparator);
  }

  public static Indent getMinLineIndent(Project project, Document document, int line1, int line2, FileType fileType) {
    CharSequence chars = document.getCharsSequence();
    CodeStyleManager codeStyleManager = CodeStyleManager.getInstance(project);
    Indent minIndent = null;
    for (int line = line1; line <= line2; line++) {
      int lineStart = document.getLineStartOffset(line);
      int textStart = CharArrayUtil.shiftForward(chars, lineStart, " \t");
      if (textStart >= document.getTextLength()) {
        textStart = document.getTextLength();
      }
      else {
        char c = chars.charAt(textStart);
        if (c == '\n' || c == '\r') continue; // empty line
      }
      String space = chars.subSequence(lineStart, textStart).toString();
      Indent indent = codeStyleManager.getIndent(space, fileType);
      minIndent = minIndent != null ? indent.min(minIndent) : indent;
    }
    if (minIndent == null && line1 == line2 && line1 < document.getLineCount() - 1) {
      return getMinLineIndent(project, document, line1 + 1, line1 + 1, fileType);
    }
    //if (minIndent == Integer.MAX_VALUE){
    //  minIndent = 0;
    //}
    return minIndent;
  }

  public static PsiExpression[] findExpressionOccurrences(PsiElement scope, PsiExpression expr) {
    List<PsiExpression> array = new ArrayList<PsiExpression>();
    addExpressionOccurrences(RefactoringUtil.unparenthesizeExpression(expr), array, scope);
    return array.toArray(new PsiExpression[array.size()]);
  }

  private static void addExpressionOccurrences(PsiExpression expr, List<PsiExpression> array, PsiElement scope) {
    PsiElement[] children = scope.getChildren();
    for (PsiElement child : children) {
      if (child instanceof PsiExpression) {
        if (areExpressionsEquivalent(RefactoringUtil.unparenthesizeExpression((PsiExpression)child), expr)) {
          array.add((PsiExpression)child);
          continue;
        }
      }
      addExpressionOccurrences(expr, array, child);
    }
  }

  public static PsiExpression[] findReferenceExpressions(PsiElement scope, PsiElement referee) {
    ArrayList<PsiElement> array = new ArrayList<PsiElement>();
    addReferenceExpressions(array, scope, referee);
    return array.toArray(new PsiExpression[array.size()]);
  }

  private static void addReferenceExpressions(ArrayList<PsiElement> array, PsiElement scope, PsiElement referee) {
    PsiElement[] children = scope.getChildren();
    for (PsiElement child : children) {
      if (child instanceof PsiReferenceExpression) {
        PsiElement ref = ((PsiReferenceExpression)child).resolve();
        if (ref != null && PsiEquivalenceUtil.areElementsEquivalent(ref, referee)) {
          array.add(child);
        }
      }
      addReferenceExpressions(array, child, referee);
    }
  }

  public static boolean areExpressionsEquivalent(PsiExpression expr1, PsiExpression expr2) {
    if (!PsiEquivalenceUtil.areElementsEquivalent(expr1, expr2)) return false;
    PsiType type1 = expr1.getType();
    PsiType type2 = expr2.getType();
    return Comparing.equal(type1, type2);
  }

  public static Editor positionCursor(final Project project, PsiFile targetFile, PsiElement element) {
    TextRange range = element.getTextRange();
    int textOffset = range.getStartOffset();

    OpenFileDescriptor descriptor = new OpenFileDescriptor(project, targetFile.getVirtualFile(), textOffset);
    return FileEditorManager.getInstance(project).openTextEditor(descriptor, true);
  }

  public static boolean preparePsiElementForWrite(final PsiElement element) {
    PsiFile file = element == null ? null : element.getContainingFile();
    return prepareFileForWrite(file);
  }

  public static boolean prepareFileForWrite(final PsiFile file) {
    if (file == null) return false;

    if (!file.isWritable()) {
      final Project project = file.getProject();

      final Editor editor = FileEditorManager.getInstance(project).openTextEditor(
        new OpenFileDescriptor(project, file.getVirtualFile()), true);

      final Document document = PsiDocumentManager.getInstance(project).getDocument(file);
      if (!FileDocumentManager.fileForDocumentCheckedOutSuccessfully(document, project)) {
        ApplicationManager.getApplication().invokeLater(new Runnable() {
          public void run() {
            if (editor != null && editor.getComponent().isDisplayable()) {
              HintManager.getInstance().showErrorHint(
                editor,
                CodeInsightBundle.message("error.hint.file.is.readonly", file.getVirtualFile().getPresentableUrl()));
            }
          }
        });

        return false;
      }
    }

    return true;
  }

  public static PsiFile getFormFile(PsiField field) {
    final PsiSearchHelper searchHelper = field.getManager().getSearchHelper();
    final PsiClass containingClass = field.getContainingClass();
    if (containingClass != null && containingClass.getQualifiedName() != null) {
      final PsiFile[] forms = searchHelper.findFormsBoundToClass(containingClass.getQualifiedName());
      for (PsiFile formFile : forms) {
        final PsiReference[] refs = formFile.getReferences();
        for (final PsiReference ref : refs) {
          if (ref.isReferenceTo(field)) {
            return formFile;
          }
        }
      }
    }
    return null;
  }
}
