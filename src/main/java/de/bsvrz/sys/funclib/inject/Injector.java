/*
 * Copyright (c) 2013, Falko Schumann <http://www.muspellheim.de>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 *   - Redistributions of source code must retain the above copyright notice,
 *     this list of conditions and the following disclaimer.
 *   - Redistributions in binary form must reproduce the above copyright notice,
 *     this list of conditions and the following disclaimer in the documentation
 *     and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

package de.bsvrz.sys.funclib.inject;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;

/**
 * Der Kontext für das Dependency Injection.
 *
 * @author Falko Schumann &lt;falko.schumann@muspellheim.de&gt;
 */
public class Injector {

    // TODO Abhängigkeiten rekursiv injizieren; auf Zyklen prüfen !!

    private final Map<Class<?>, Binding> bindings = new LinkedHashMap<Class<?>, Binding>();
    private final Map<Class<?>, Object> singletons = new LinkedHashMap<Class<?>, Object>();

    /**
     * @throws NullPointerException
     *         wenn der Parameter <code>type</code> den Wert <code>null</code> hat.
     */
    public Binding addBinding(Class<?> type) {
        if (type == null) throw new NullPointerException("type");

        Binding binding = new Binding(type);
        bindings.put(type, binding);
        return binding;
    }

    /**
     * @throws NullPointerException
     *         wenn der Parameter <code>clazz</code> den Wert <code>null</code> hat.
     */
    public <T> T make(Class<T> clazz) {
        final boolean isSingleton = clazz.isAnnotationPresent(Singleton.class);
        if (isSingleton && singletons.containsKey(clazz))
            return (T) singletons.get(clazz);
        try {
            T result = injectConstructor(clazz);
            injectFields(result);
            injectMethods(result);
            if (isSingleton)
                singletons.put(clazz, result);
            return result;
        } catch (RuntimeException ex) {
            throw new InjectionException(
                    "Eine Instanz der Klasse kann nicht erzeugt werden: " + clazz, ex);
        }
    }

    private <T> T injectConstructor(Class<T> type) {
        if (bindings.containsKey(type))
            type = (Class<T>) bindings.get(type).implementationClass;

        Constructor<T> constructor = getInjectableConstructors(type);
        if (constructor != null)
            return invoke(constructor);

        return BeanUtil.createObject(type);
    }

    private <T> Constructor<T> getInjectableConstructors(Class<T> type) {
        Constructor<T> result = null;
        for (Constructor<?> e : type.getDeclaredConstructors()) {
            if (isInjectable(e)) {
                if (result != null)
                    throw new InjectionException(
                            "Es darf nur einen injizierbaren Konstruktor geben: " + type);

                e.setAccessible(true);
                result = (Constructor<T>) e;
            }
        }
        return result;
    }

    private boolean isInjectable(AnnotatedElement element) {
        return element.isAnnotationPresent(Inject.class);
    }

    private <T> T invoke(Constructor<T> constructor) {
        final Class<?>[] parameterTypes = constructor.getParameterTypes();
        final Type[] types = constructor.getGenericParameterTypes();
        Object[] arguments = new Object[parameterTypes.length];
        for (int i = 0; i < parameterTypes.length; i++) {
            Class<?> parameterType = parameterTypes[i];
            if (Provider.class.isAssignableFrom(parameterType)) {
                ParameterizedType parameterizedType = (ParameterizedType) types[i];
                arguments[i] = new ProviderImpl((Class<?>) parameterizedType.getActualTypeArguments()[0], this);
            } else {
                arguments[i] = make(parameterType);
            }
        }
        return BeanUtil.createObject(constructor, arguments);
    }

    private <T> void injectFields(T result) {
        // TODO Zuerst die Felder der Superklasse injizieren
        for (Field e : result.getClass().getDeclaredFields()) {
            if (isInjectable(e)) {
                e.setAccessible(true);
                final Class<?> fieldType = e.getType();
                Object value;
                if (Provider.class.isAssignableFrom(fieldType)) {
                    ParameterizedType parameterizedType = (ParameterizedType) e.getGenericType();
                    value = new ProviderImpl((Class<?>) parameterizedType.getActualTypeArguments()[0], this);
                    BeanUtil.setField(result, e, value);
                } else {
                    value = make(fieldType);
                }
                BeanUtil.setField(result, e, value);
            }
        }
    }

    private <T> void injectMethods(T result) {
        // TODO Zuerst die Methoden der Superklasse injizieren
        for (Method e : result.getClass().getDeclaredMethods()) {
            if (isInjectable(e)) {
                e.setAccessible(true);
                invoke(result, e);
            }
        }

    }

    private void invoke(Object object, Method method) {
        final Class<?>[] parameterTypes = method.getParameterTypes();
        final Type[] types = method.getGenericParameterTypes();
        Object[] arguments = new Object[parameterTypes.length];
        for (int i = 0; i < parameterTypes.length; i++) {
            Class<?> parameterType = parameterTypes[i];
            if (Provider.class.isAssignableFrom(parameterType)) {
                ParameterizedType parameterizedType = (ParameterizedType) types[i];
                arguments[i] = new ProviderImpl((Class<?>) parameterizedType.getActualTypeArguments()[0], this);
            } else {
                arguments[i] = make(parameterType);
            }
        }
        BeanUtil.invokeMethod(object, method, arguments);
    }

}
