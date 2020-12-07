package org.terasology.context;

import java.lang.annotation.Annotation;
import java.util.List;

public interface AnnotationMetadata {

    List<AnnotationValue> getAnnotationsByStereotype(Class<? extends Annotation> stereotype);

    List<AnnotationValue> getAnnotationsByStereotype(String stereotype);

    List<AnnotationValue> findAnnotations(String annotation);

    List<AnnotationValue> findAnnotations(Class<? extends Annotation> annotation);

    boolean hasAnnotation(Class<? extends Annotation> annotation);

    boolean hasAnnotation(String annotation);

    boolean hasStereotype(Class<? extends Annotation> annotation);

    boolean hasStereotype(String annotation);

    Object getRawSingleValue(String annotation, String field);
}
