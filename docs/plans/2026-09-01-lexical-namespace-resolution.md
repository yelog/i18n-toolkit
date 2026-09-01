# Lexical Namespace Resolution Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Resolve i18n namespaces from the translation function's actual lexical binding without allowing nested or sibling functions to contribute an unrelated namespace.

**Architecture:** Resolve the `t`/`$t` reference at the call site back to the declaration that receives a supported translation hook. Represent the result as three states so an absent binding is distinguishable from a binding with a default or dynamic namespace; use a scope-limited fallback only when JavaScript PSI cannot resolve the reference. Keep the public `resolveNamespace()` and `getFullKey()` contracts unchanged so annotators, completion, navigation, documentation, folding, rename, and usage search all receive the correction centrally.

**Tech Stack:** Kotlin 2.3, IntelliJ JavaScript PSI, IntelliJ Platform test fixtures, Gradle Wrapper, JDK 21.

---

### Task 1: Lock Down Lexical-Scope Semantics With Regression Tests

**Files:**
- Modify: `src/test/kotlin/com/github/yelog/i18ntoolkit/I18nNamespaceTest.kt`

**Step 1: Add a test for a shadowed default-namespace binding**

Add `testNestedTranslationBindingDoesNotInheritOuterNamespace()` using this TSX fixture:

```tsx
import { useTranslations } from 'next-intl'

export default function Page() {
  const t = useTranslations('outer')

  function Child() {
    const t = useTranslations()
    return t('title')
  }

  return <Child />
}
```

Locate `title` through `I18nKeyExtractor.findKeyAtOffset()` and assert that `candidate.fullKey == "title"`. Finding `useTranslations()` in the nearest scope must stop outer lookup even though the hook has no static namespace.

**Step 2: Add a test for a shadowed dynamic namespace**

Add `testDynamicNestedTranslationBindingDoesNotInheritOuterNamespace()`:

```tsx
export default function Page({ namespace }) {
  const t = useTranslations('outer')

  function Child() {
    const t = useTranslations(namespace)
    return t('title')
  }

  return <Child />
}
```

Assert that the full key is `title`. A dynamic namespace cannot be statically prepended, but it still proves that the inner `t` shadows the outer binding.

**Step 3: Add a test for sibling-function isolation**

Add `testSiblingNestedFunctionCannotProvideNamespace()`:

```tsx
export default function Page() {
  function Child() {
    const t = useTranslations('child')
    return t('childTitle')
  }

  const handleClick = () => t('pageTitle')
  return <Child />
}
```

Assert that the full key at `pageTitle` is `pageTitle`, not `child.pageTitle`.

**Step 4: Add a test for an unrelated hook binding**

Add `testHookAssignedToDifferentVariableCannotProvideNamespace()`:

```tsx
export default function Page() {
  const translate = useTranslations('unrelated')
  return t('title')
}
```

Assert that the full key is `title`. Namespace lookup must match the called translation-function binding, not any supported hook in the function body.

**Step 5: Preserve valid direct, destructured, and closure cases**

Keep the merged nested-callback test and add focused assertions for both supported declaration shapes:

```tsx
const t = useTranslations('next')
t('title')
```

```tsx
const { t } = useTranslation('common')
const handler = () => t('title')
```

Expect `next.title` and `common.title` respectively. These tests prevent a binding-aware implementation from fixing isolation by breaking current next-intl and react-i18next behavior.

**Step 6: Run the new tests and confirm the current implementation fails only on the new edge cases**

Run:

```bash
./gradlew test --tests "com.github.yelog.i18ntoolkit.I18nNamespaceTest"
```

Expected before implementation: the merged nested-callback test and direct/destructured cases pass; shadowed, sibling, and unrelated-binding tests fail with incorrectly prefixed keys.

**Step 7: Commit the regression tests**

```bash
git add src/test/kotlin/com/github/yelog/i18ntoolkit/I18nNamespaceTest.kt
git commit -m "test(i18n): cover lexical namespace isolation"
```

### Task 2: Model Namespace Lookup Without Ambiguous Empty Strings

**Files:**
- Modify: `src/main/kotlin/com/github/yelog/i18ntoolkit/util/I18nNamespaceResolver.kt:50-175`
- Test: `src/test/kotlin/com/github/yelog/i18ntoolkit/I18nNamespaceTest.kt`

**Step 1: Add an internal three-state result**

Inside `I18nNamespaceResolver`, introduce a private sealed type equivalent to:

```kotlin
private sealed interface NamespaceBinding {
    data object NotFound : NamespaceBinding
    data object BoundWithoutStaticNamespace : NamespaceBinding
    data class Static(val namespace: String) : NamespaceBinding
}
```

Semantics:

- `NotFound`: no binding for the called translation function was found; outer lexical lookup or configured default namespace may still apply.
- `BoundWithoutStaticNamespace`: the called function is bound to a supported hook, but the hook is unnamespaced or dynamic; stop outer lookup and return no explicit prefix.
- `Static`: the called function is bound to a supported hook with a statically known namespace.

**Step 2: Change hook argument extraction to return the three-state result**

Replace `extractNamespaceFromHook(): String` with `extractNamespaceBinding(): NamespaceBinding`.

Return `BoundWithoutStaticNamespace` for:

- no arguments,
- a non-literal first argument,
- an empty array,
- an object without a literal `namespace` or `ns` property.

Return `Static(namespace)` only for a nonblank literal namespace. Do not represent either state with `""`.

**Step 3: Keep public behavior stable**

Keep `resolveNamespace(tCallExpression: JSCallExpression): String` public and continue returning either `"namespace."` or `""`. Convert the internal result only at this boundary:

```kotlin
return when (val binding = resolveNamespaceBinding(tCallExpression)) {
    is NamespaceBinding.Static -> "${binding.namespace}."
    NamespaceBinding.NotFound,
    NamespaceBinding.BoundWithoutStaticNamespace -> ""
}
```

This avoids changing the 14 existing feature call sites.

**Step 4: Run the focused tests**

Run:

```bash
./gradlew test --tests "com.github.yelog.i18ntoolkit.I18nNamespaceTest"
```

Expected: tests may still fail until declaration resolution is implemented, but failures must no longer be caused by conflating an unnamespaced hook with an absent hook.

### Task 3: Resolve the Translation Function's Actual Declaration

**Files:**
- Modify: `src/main/kotlin/com/github/yelog/i18ntoolkit/util/I18nNamespaceResolver.kt:50-175`
- Test: `src/test/kotlin/com/github/yelog/i18ntoolkit/I18nNamespaceTest.kt`

**Step 1: Resolve the called reference**

Read `tCallExpression.methodExpression` as `JSReferenceExpression`. Resolve that reference through IntelliJ PSI and retain the declaration element for the called identifier. If the method expression is qualified, only accept a declaration shape already supported by the plugin; do not infer a namespace from unrelated calls in the file.

**Step 2: Find the declaration's initializer hook**

From the resolved declaration, inspect the smallest declaration container that owns it and support the two established forms:

```tsx
const t = useTranslations('namespace')
```

```tsx
const { t } = useTranslation('namespace')
```

The hook call must initialize the declaration that contains the resolved `t`; merely appearing elsewhere in the same function is insufficient. Accept only hook names in `translationHooks`.

**Step 3: Return a binding even when its namespace is not static**

Once the declaration is proven to originate from `useTranslation`, `useI18n`, or `useTranslations`, call `extractNamespaceBinding()`. Propagate `BoundWithoutStaticNamespace` rather than looking through to an outer declaration.

**Step 4: Verify closure capture**

For the merged PR scenario, the `t` reference inside `handleAcceptDiscountOffer` should resolve directly to the declaration in `Page`. It should produce `Static("Subscriptions.Fresh.Cancellation.SecondarySaveTactics.Discount")` without scanning every enclosing function body.

**Step 5: Run the focused tests**

Run:

```bash
./gradlew test --tests "com.github.yelog.i18ntoolkit.I18nNamespaceTest"
```

Expected: direct binding, destructured binding, nested closure, inner shadowing, dynamic namespace, sibling isolation, and unrelated-hook tests all pass.

### Task 4: Add a Safe Fallback for Incomplete or Copied PSI

**Files:**
- Modify: `src/main/kotlin/com/github/yelog/i18ntoolkit/util/I18nNamespaceResolver.kt`
- Test: `src/test/kotlin/com/github/yelog/i18ntoolkit/I18nNamespaceTest.kt`

**Step 1: Use fallback only when reference resolution is unavailable**

Completion and editor features may operate on copied or temporarily incomplete PSI. If `JSReferenceExpression.resolve()` cannot return a declaration, walk from the nearest containing function outward, but never recursively inspect arbitrary nested functions.

**Step 2: Make fallback traversal respect function boundaries**

Replace the unrestricted `PsiRecursiveElementVisitor` behavior. While inspecting one lexical scope:

- visit that scope's own statements and expressions,
- do not descend into a nested `JSFunction` or `JSFunctionExpression`,
- allow traversal of the root scope being inspected,
- consider only a hook whose assigned identifier matches `methodExpression.referenceName`.

This prevents `Child` from supplying a namespace to `handleClick` and prevents an unrelated `translate` declaration from supplying a namespace to `t`.

**Step 3: Stop fallback lookup on shadowing**

If the current lexical scope declares the called identifier from a supported hook, return its binding result immediately, including `BoundWithoutStaticNamespace`. Only continue outward for `NotFound`.

**Step 4: Add a copied-PSI regression test if the fixture can reproduce unresolved references**

Create a PSI copy through the same mechanism used by completion, place a nested callback call in the copy, and assert that the namespace still resolves from the outer component. If the platform fixture always resolves the reference, document that limitation in the test and keep the fallback small and independently testable.

**Step 5: Run namespace and completion tests**

Run:

```bash
./gradlew test --tests "com.github.yelog.i18ntoolkit.I18nNamespaceTest" --tests "*Completion*"
```

Expected: all selected tests pass.

**Step 6: Commit the resolver implementation**

```bash
git add src/main/kotlin/com/github/yelog/i18ntoolkit/util/I18nNamespaceResolver.kt src/test/kotlin/com/github/yelog/i18ntoolkit/I18nNamespaceTest.kt
git commit -m "fix(i18n): resolve namespaces by lexical binding"
```

### Task 5: Verify All Features That Consume Full Keys

**Files:**
- Verify: `src/main/kotlin/com/github/yelog/i18ntoolkit/util/I18nNamespaceResolver.kt`
- Verify: `src/main/kotlin/com/github/yelog/i18ntoolkit/completion/I18nKeyCompletionContributor.kt`
- Verify: `src/main/kotlin/com/github/yelog/i18ntoolkit/annotator/I18nKeyAnnotator.kt`
- Verify: `src/main/kotlin/com/github/yelog/i18ntoolkit/reference/I18nReferenceContributor.kt`
- Verify: `src/main/kotlin/com/github/yelog/i18ntoolkit/usages/I18nFindUsagesHandlerFactory.kt`
- Verify: `src/main/kotlin/com/github/yelog/i18ntoolkit/hint/I18nInlayHintsProvider.kt`

**Step 1: Run all namespace tests**

```bash
./gradlew test --tests "com.github.yelog.i18ntoolkit.I18nNamespaceTest"
```

Expected: `BUILD SUCCESSFUL`.

**Step 2: Run the complete test suite**

```bash
./gradlew check
```

Expected: `BUILD SUCCESSFUL`. If the existing `PackageJsonData` `NoSuchMethodError` in the rename tests remains reproducible on unchanged `main`, record it separately and do not weaken namespace assertions to make the build green.

**Step 3: Check formatting and unintended changes**

```bash
git diff --check
git status --short
git diff -- src/main/kotlin/com/github/yelog/i18ntoolkit/util/I18nNamespaceResolver.kt src/test/kotlin/com/github/yelog/i18ntoolkit/I18nNamespaceTest.kt
```

Expected: no whitespace errors and only the resolver, tests, and any explicitly justified changelog adjustment are changed.

**Step 4: Perform an IDE smoke test when practical**

Run:

```bash
./gradlew runIde
```

Open a next-intl TSX sample containing a nested callback, a nested child component with a shadowed `t`, and a sibling component. Verify inline hints, unresolved-key annotation, completion, Quick Documentation, and navigation use the correct full key in each location.

**Step 5: Commit any final test-only adjustment**

Only if verification required a legitimate test or documentation correction:

```bash
git add <intended-files>
git commit -m "test(i18n): verify namespace consumers"
```
