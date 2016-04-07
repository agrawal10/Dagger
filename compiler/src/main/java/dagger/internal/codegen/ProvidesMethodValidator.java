/*
 * Copyright (C) 2014 Google, Inc.
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
package dagger.internal.codegen;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import dagger.Module;
import dagger.Provides;
import java.util.Set;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

import static com.google.auto.common.MoreElements.isAnnotationPresent;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static dagger.internal.codegen.ErrorMessages.BINDING_METHOD_ABSTRACT;
import static dagger.internal.codegen.ErrorMessages.BINDING_METHOD_MUST_RETURN_A_VALUE;
import static dagger.internal.codegen.ErrorMessages.BINDING_METHOD_NOT_IN_MODULE;
import static dagger.internal.codegen.ErrorMessages.BINDING_METHOD_NOT_MAP_HAS_MAP_KEY;
import static dagger.internal.codegen.ErrorMessages.BINDING_METHOD_PRIVATE;
import static dagger.internal.codegen.ErrorMessages.BINDING_METHOD_SET_VALUES_RAW_SET;
import static dagger.internal.codegen.ErrorMessages.BINDING_METHOD_TYPE_PARAMETER;
import static dagger.internal.codegen.ErrorMessages.BINDING_METHOD_WITH_MULTIPLE_MAP_KEY;
import static dagger.internal.codegen.ErrorMessages.BINDING_METHOD_WITH_NO_MAP_KEY;
import static dagger.internal.codegen.ErrorMessages.PROVIDES_METHOD_SET_VALUES_RETURN_SET;
import static dagger.internal.codegen.ErrorMessages.provisionMayNotDependOnProducerType;
import static dagger.internal.codegen.MapKeys.getMapKeys;
import static dagger.internal.codegen.Validation.validateMethodQualifiers;
import static dagger.internal.codegen.Validation.validateReturnType;
import static dagger.internal.codegen.Validation.validateUncheckedThrows;
import static javax.lang.model.element.Modifier.ABSTRACT;
import static javax.lang.model.element.Modifier.PRIVATE;
import static javax.lang.model.type.TypeKind.DECLARED;
import static javax.lang.model.type.TypeKind.VOID;

/**
 * A {@linkplain ValidationReport validator} for {@link Provides} methods.
 *
 * @author Gregory Kick
 * @since 2.0
 */
final class ProvidesMethodValidator {
  private final Elements elements;
  private final Types types;

  ProvidesMethodValidator(Elements elements, Types types) {
    this.elements = checkNotNull(elements);
    this.types = checkNotNull(types);
  }

  private TypeElement getSetElement() {
    return elements.getTypeElement(Set.class.getCanonicalName());
  }

  ValidationReport<ExecutableElement> validate(ExecutableElement providesMethodElement) {
    ValidationReport.Builder<ExecutableElement> builder =
        ValidationReport.about(providesMethodElement);

    Provides providesAnnotation = providesMethodElement.getAnnotation(Provides.class);
    checkArgument(providesAnnotation != null);

    Element enclosingElement = providesMethodElement.getEnclosingElement();
    if (!isAnnotationPresent(enclosingElement, Module.class)) {
      builder.addError(
          formatModuleErrorMessage(BINDING_METHOD_NOT_IN_MODULE), providesMethodElement);
    }

    if (!providesMethodElement.getTypeParameters().isEmpty()) {
      builder.addError(formatErrorMessage(BINDING_METHOD_TYPE_PARAMETER), providesMethodElement);
    }

    Set<Modifier> modifiers = providesMethodElement.getModifiers();
    if (modifiers.contains(PRIVATE)) {
      builder.addError(formatErrorMessage(BINDING_METHOD_PRIVATE), providesMethodElement);
    }
    if (modifiers.contains(ABSTRACT)) {
      builder.addError(formatErrorMessage(BINDING_METHOD_ABSTRACT), providesMethodElement);
    }

    TypeMirror returnType = providesMethodElement.getReturnType();
    TypeKind returnTypeKind = returnType.getKind();
    if (returnTypeKind.equals(VOID)) {
      builder.addError(
          formatErrorMessage(BINDING_METHOD_MUST_RETURN_A_VALUE), providesMethodElement);
    }

    validateUncheckedThrows(elements, types, providesMethodElement, Provides.class, builder);

    for (VariableElement parameter : providesMethodElement.getParameters()) {
      if (FrameworkTypes.isProducerType(parameter.asType())) {
        builder.addError(provisionMayNotDependOnProducerType(parameter.asType()), parameter);
      }
    }

    // check mapkey is right
    if (!providesAnnotation.type().equals(Provides.Type.MAP)
        && !getMapKeys(providesMethodElement).isEmpty()) {
      builder.addError(
          formatErrorMessage(BINDING_METHOD_NOT_MAP_HAS_MAP_KEY), providesMethodElement);
    }

    validateMethodQualifiers(builder, providesMethodElement);

    switch (providesAnnotation.type()) {
      case UNIQUE: // fall through
      case SET:
        validateReturnType(Provides.class, builder, returnType);
        break;
      case MAP:
        validateReturnType(Provides.class, builder, returnType);
        ImmutableSet<? extends AnnotationMirror> mapKeys = getMapKeys(providesMethodElement);
        switch (mapKeys.size()) {
          case 0:
            builder.addError(
                formatErrorMessage(BINDING_METHOD_WITH_NO_MAP_KEY), providesMethodElement);
            break;
          case 1:
            break;
          default:
            builder.addError(
                formatErrorMessage(BINDING_METHOD_WITH_MULTIPLE_MAP_KEY), providesMethodElement);
            break;
        }
        break;
      case SET_VALUES:
        if (!returnTypeKind.equals(DECLARED)) {
          builder.addError(PROVIDES_METHOD_SET_VALUES_RETURN_SET, providesMethodElement);
        } else {
          DeclaredType declaredReturnType = (DeclaredType) returnType;
          // TODO(gak): should we allow "covariant return" for set values?
          if (!declaredReturnType.asElement().equals(getSetElement())) {
            builder.addError(PROVIDES_METHOD_SET_VALUES_RETURN_SET, providesMethodElement);
          } else if (declaredReturnType.getTypeArguments().isEmpty()) {
            builder.addError(
                formatErrorMessage(BINDING_METHOD_SET_VALUES_RAW_SET), providesMethodElement);
          } else {
            validateReturnType(
                Provides.class,
                builder,
                Iterables.getOnlyElement(declaredReturnType.getTypeArguments()));
          }
        }
        break;
      default:
        throw new AssertionError();
    }

    return builder.build();
  }

  private String formatErrorMessage(String msg) {
    return String.format(msg, Provides.class.getSimpleName());
  }

  private String formatModuleErrorMessage(String msg) {
    return String.format(msg, Provides.class.getSimpleName(), Module.class.getSimpleName());
  }
}
