package act.apidoc;

/*-
 * #%L
 * ACT Framework
 * %%
 * Copyright (C) 2014 - 2017 ActFramework
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

import act.Act;
import act.app.data.StringValueResolverManager;
import act.conf.AppConfig;
import act.data.DataPropertyRepository;
import act.data.Sensitive;
import act.handler.RequestHandler;
import act.handler.builtin.controller.RequestHandlerProxy;
import act.handler.builtin.controller.impl.ReflectedHandlerInvoker;
import act.inject.DefaultValue;
import act.inject.DependencyInjector;
import act.inject.param.ParamValueLoaderService;
import act.util.FastJsonPropertyPreFilter;
import act.util.PropertySpec;
import act.validation.NotBlank;
import com.alibaba.fastjson.JSON;
import org.apache.bval.constraints.NotEmpty;
import org.joda.time.*;
import org.osgl.$;
import org.osgl.http.H;
import org.osgl.inject.BeanSpec;
import org.osgl.logging.Logger;
import org.osgl.mvc.result.Result;
import org.osgl.storage.ISObject;
import org.osgl.util.*;

import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;
import javax.validation.constraints.NotNull;

/**
 * An `Endpoint` represents an API that provides specific service
 */
public class Endpoint implements Comparable<Endpoint> {

    private static final Logger LOGGER = ApiManager.LOGGER;

    private static BeanSpecInterpreter beanSpecInterpretor = new BeanSpecInterpreter();

    public static class ParamInfo {
        private String bindName;
        private BeanSpec beanSpec;
        private String description;
        private String defaultValue;
        private boolean required;
        private List<String> options;

        private ParamInfo(String bindName, BeanSpec beanSpec, String description) {
            this.bindName = bindName;
            this.beanSpec = beanSpec;
            this.description = description;
            this.defaultValue = checkDefaultValue(beanSpec);
            this.required = checkRequired(beanSpec);
            this.options = checkOptions(beanSpec);
        }

        public String getName() {
            return bindName;
        }

        public String getType() {
            return beanSpecInterpretor.interpret(beanSpec);
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public boolean isRequired() {
            return required;
        }

        public List<String> getOptions() {
            return options;
        }

        public String getDefaultValue() {
            return defaultValue;
        }

        private String checkDefaultValue(BeanSpec spec) {
            DefaultValue def = spec.getAnnotation(DefaultValue.class);
            if (null != def) {
                return def.value();
            }
            Class<?> type = spec.rawType();
            if (type.isPrimitive()) {
                Object o = Act.app().resolverManager().resolve("", type);
                return null != o ? o.toString() : null;
            }
            return null;
        }

        private boolean checkRequired(BeanSpec spec) {
            return (spec.hasAnnotation(NotNull.class)
                    || spec.hasAnnotation(NotBlank.class)
                    || spec.hasAnnotation(NotEmpty.class));
        }

        private List<String> checkOptions(BeanSpec spec) {
            Class<?> type = spec.rawType();
            if (type.isEnum()) {
                return C.listOf(type.getEnumConstants()).map($.F.asString());
            }
            return null;
        }
    }

    /**
     * The scheme defines the protocol used to access the endpoint
     *
     * At the moment we support HTTP only
     */
    public enum Scheme {
        HTTP
    }

    /**
     * unique identify an endpoint in an application.
     */
    private String id;

    /**
     * The scheme used to access the endpoint
     */
    private Scheme scheme = Scheme.HTTP;

    private int port;

    /**
     * The HTTP method
     */
    private H.Method httpMethod;

    /**
     * The URL path
     */
    private String path;

    /**
     * The handler.
     *
     * In most case should be `pkg.Class.method`
     */
    private String handler;

    FastJsonPropertyPreFilter fastJsonPropertyPreFilter;

    /**
     * The description
     */
    private String description;

    private Class<?> returnType;

    private String returnSample;

    /**
     * Param list.
     *
     * Only available when handler is driven by
     * {@link act.handler.builtin.controller.impl.ReflectedHandlerInvoker}
     */
    private List<ParamInfo> params = new ArrayList<>();

    private String sampleJsonPost;
    private String sampleQuery;
    private Class<?> controllerClass;
    private Locale defLocale;

    Endpoint(int port, H.Method httpMethod, String path, RequestHandler handler) {
        AppConfig conf = Act.appConfig();
        this.httpMethod = $.requireNotNull(httpMethod);
        String urlContext = conf.urlContext();
        this.path = null == urlContext || path.startsWith("/~/") ? $.requireNotNull(path) : S.concat(urlContext, $.requireNotNull(path));
        this.handler = handler.toString();
        this.port = port;
        this.defLocale = conf.locale();
        explore(handler);
    }

    @Override
    public int compareTo(Endpoint o) {
        int n = path.compareTo(o.path);
        if (0 != n) {
            return n;
        }
        return httpMethod.ordinal() - o.httpMethod.ordinal();
    }

    public String getId() {
        return id;
    }

    /**
     * Returns extends id. This is the concatenation of
     * {@link #httpMethod} and {@link #id}. This will
     * be used by the frontend UI.
     *
     * @return the extended id
     */
    public String getXid() {
        return S.concat(httpMethod, id.replace('.', '_'));
    }

    public Scheme getScheme() {
        return scheme;
    }

    public int getPort() {
        return port;
    }

    public H.Method getHttpMethod() {
        return httpMethod;
    }

    public String getPath() {
        return path;
    }

    public String getHandler() {
        return handler;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<ParamInfo> getParams() {
        return params;
    }

    public Class<?> returnType() {
        return returnType;
    }

    public String getReturnSample() {
        return returnSample;
    }

    public String getReturnType() {
        if (void.class == returnType || Void.class == returnType) {
            return null;
        }
        return className(returnType);
    }

    public String getSampleJsonPost() {
        return sampleJsonPost;
    }

    public String getSampleQuery() {
        return sampleQuery;
    }

    public Class<?> controllerClass() {
        return controllerClass;
    }

    private void explore(RequestHandler handler) {
        RequestHandlerProxy proxy = $.cast(handler);
        ReflectedHandlerInvoker invoker = $.cast(proxy.actionHandler().invoker());
        Class<?> controllerClass = invoker.controllerClass();
        Method method = invoker.method();
        returnType = method.getReturnType();
        PropertySpec pspec = method.getAnnotation(PropertySpec.class);
        if (null != pspec) {
            PropertySpec.MetaInfo propSpec = new PropertySpec.MetaInfo();
            for (String v : pspec.value()) {
                propSpec.onValue(v);
            }
            for (String v : pspec.http()) {
                propSpec.onValue(v);
            }
            List<String> outputs = propSpec.outputFieldsForHttp();
            Set<String> excluded = propSpec.excludeFieldsForHttp();
            if (!(outputs.isEmpty() && excluded.isEmpty())) {
                fastJsonPropertyPreFilter = new FastJsonPropertyPreFilter(returnType, outputs, excluded, Act.app().service(DataPropertyRepository.class));
            }
            // just ignore cli value here
        }
        this.id = id(method);
        returnSample = generateSampleJson(BeanSpec.of(method.getGenericReturnType(), null, Act.injector()));
        Description descAnno = method.getAnnotation(Description.class);
        this.description = null == descAnno ? methodDescription(method) : descAnno.value();
        exploreParamInfo(method);
        if (!Modifier.isStatic(method.getModifiers())) {
            exploreParamInfo(controllerClass);
        }
        this.controllerClass = controllerClass;
    }

    private String methodDescription(Method method) {
        return id(method);
    }

    private String id(Method method) {
        Class<?> hosting = method.getDeclaringClass();
        return className(hosting) + "." + method.getName();
    }

    private String className(Class<?> clz) {
        Class<?> enclosing = clz.getEnclosingClass();
        if (null != enclosing) {
            return className(enclosing) + "." + clz.getSimpleName();
        }
        return clz.getSimpleName();
    }

    private void exploreParamInfo(Method method) {
        Type[] paramTypes = method.getGenericParameterTypes();
        int paramCount = paramTypes.length;
        if (0 == paramCount) {
            return;
        }
        DependencyInjector injector = Act.injector();
        Annotation[][] allAnnos = method.getParameterAnnotations();
        Map<String, Object> sampleData = new HashMap<>();
        StringValueResolverManager resolver = Act.app().resolverManager();
        List<String> sampleQuery = new ArrayList<>();
        for (int i = 0; i < paramCount; ++i) {
            Type type = paramTypes[i];
            Annotation[] annos = allAnnos[i];
            ParamInfo info = paramInfo(type, annos, injector, null);
            if (null != info) {
                params.add(info);
                if (path.contains("{" + info.getName() + "}")) {
                    // no sample data for URL path variable
                    continue;
                }
                Object sample;
                if (null != info.defaultValue) {
                    sample = resolver.resolve(info.defaultValue, info.beanSpec.rawType());
                } else {
                    sample = generateSampleData(info.beanSpec, new HashSet<Type>(), new ArrayList<String>());
                }
                if (H.Method.GET == this.httpMethod) {
                    String query = generateSampleQuery(info.beanSpec.withoutName(), info.bindName, new HashSet<Type>(), C.<String>newList());
                    if (S.notBlank(query)) {
                        sampleQuery.add(query);
                    }
                } else {
                    sampleData.put(info.bindName, sample);
                }
            }
        }
        if (!sampleData.isEmpty()) {
            sampleJsonPost = JSON.toJSONString(sampleData, true);
        }
        if (!sampleQuery.isEmpty()) {
            this.sampleQuery = S.join("&", sampleQuery);
        }
    }

    // we don't need fields declared in `@NoBind` or `@Stateless` classes
    private static final $.Predicate<Field> FIELD_PREDICATE = new $.Predicate<Field>() {
        @Override
        public boolean test(Field field) {
            return !ParamValueLoaderService.shouldWaive(field);
        }
    };

    private void exploreParamInfo(Class<?> controller) {
        DependencyInjector injector = Act.injector();
        List<Field> fields = $.fieldsOf(controller, FIELD_PREDICATE);
        for (Field field : fields) {
            Type type = field.getGenericType();
            Annotation[] annos = field.getAnnotations();
            ParamInfo info = paramInfo(type, annos, injector, field.getName());
            if (null != info) {
                params.add(info);
            }
        }
    }

    private ParamInfo paramInfo(Type type, Annotation[] annos, DependencyInjector injector, String name) {
        if (isLoginUser(annos)) {
            return null;
        }
        BeanSpec spec = BeanSpec.of(type, annos, name, injector);
        if (ParamValueLoaderService.providedButNotDbBind(spec, injector)) {
            return null;
        }
        if (ParamValueLoaderService.hasDbBind(spec.allAnnotations())) {
            if (org.osgl.util.S.blank(name)) {
                name = spec.name();
            }
            return new ParamInfo(name, BeanSpec.of(String.class, Act.injector()), name + " id");
        }
        String description = "";
        Description descAnno = spec.getAnnotation(Description.class);
        if (null != descAnno) {
            description = descAnno.value();
        }
        return new ParamInfo(spec.name(), spec, description);
    }

    private boolean isLoginUser(Annotation[] annos) {
        for (Annotation a : annos) {
            if ("LoginUser".equals(a.annotationType().getSimpleName())) {
                return true;
            }
        }
        return false;
    }

    private String generateSampleJson(BeanSpec spec) {
        Class<?> type = spec.rawType();
        if (Result.class.isAssignableFrom(type)) {
            return null;
        }
        Object sample = generateSampleData(spec, new HashSet<Type>(), new ArrayList<String>());
        if (null == sample) {
            return null;
        }
        if ($.isSimpleType(type)) {
            sample = C.Map("result", sample);
        }
        return JSON.toJSONString(sample, true);
    }

    private String generateSampleQuery(BeanSpec spec, String bindName, Set<Type> typeChain, List<String> nameChain) {
        Class<?> type = spec.rawType();
        String specName = spec.name();
        if (S.notBlank(specName)) {
            nameChain.add(specName);
        }
        if ($.isSimpleType(type)) {
            Object o = generateSampleData(spec, typeChain, nameChain);
            if (null == o) {
                return "";
            }
            return bindName + "=" + o;
        }
        if (type.isArray()) {
            // TODO handle datetime component type
            Class<?> elementType = type.getComponentType();
            BeanSpec elementSpec = BeanSpec.of(elementType, Act.injector());
            if ($.isSimpleType(elementType)) {
                Object o = generateSampleData(elementSpec, typeChain, nameChain);
                if (null == o) {
                    return "";
                }
                return bindName + "=" + o
                        + "&" + bindName + "=" + generateSampleData(elementSpec, typeChain, nameChain);
            }
        } else if (Collection.class.isAssignableFrom(type)) {
            // TODO handle datetime component type
            List<Type> typeParams = spec.typeParams();
            Type elementType = typeParams.isEmpty() ? Object.class : typeParams.get(0);
            BeanSpec elementSpec = BeanSpec.of(elementType, null, Act.injector());
            if ($.isSimpleType(elementSpec.rawType())) {
                Object o = generateSampleData(elementSpec, typeChain, nameChain);
                if (null == o) {
                    return "";
                }
                return bindName + "=" + o
                        + "&" + bindName + "=" + generateSampleData(elementSpec, typeChain, nameChain);
            }
        } else if (Map.class.isAssignableFrom(type)) {
            LOGGER.warn("Map not supported yet");
            return "";
        } else if (ReadableInstant.class.isAssignableFrom(type)) {
            return bindName + "=<datetime>";
        }
        if (null != stringValueResolver(type)) {
            return bindName + "=" + S.random(5);
        }
        List<String> queryPairs = new ArrayList<>();
        List<Field> fields = $.fieldsOf(type);
        for (Field field : fields) {
            if (ParamValueLoaderService.shouldWaive(field)) {
                continue;
            }
            String fieldBindName = bindName + "." + field.getName();
            String pair = generateSampleQuery(BeanSpec.of(field, Act.injector()), fieldBindName, C.newSet(typeChain), C.newList(nameChain));
            if (S.notBlank(pair)) {
                queryPairs.add(pair);
            }
        }
        return S.join(queryPairs).by("&").get();
    }

    private static boolean isCollection(Type type) {
        if (type instanceof Class) {
            Class clazz = $.cast(type);
            if (Iterable.class.isAssignableFrom(clazz)) {
                return true;
            }
            return false;
        }
        if (type instanceof ParameterizedType) {
            ParameterizedType ptype = $.cast(type);
            return isCollection(ptype.getRawType());
        }
        return false;
    }

    private Object generateSampleData(BeanSpec spec, Set<Type> typeChain, List<String> nameChain) {
        Type type = spec.type();
        if (typeChain.contains(type) && !isCollection(type)) {
            return S.concat(spec.name(), ":", type); // circular reference detected
        }
        typeChain.add(type);
        String name = spec.name();
        if (S.notBlank(name)) {
            nameChain.add(name);
        }
        if (null != fastJsonPropertyPreFilter) {
            String path = S.join(nameChain).by(".").get();
            if (!fastJsonPropertyPreFilter.matches(path)) {
                return null;
            }
        }
        Class<?> classType = spec.rawType();
        try {
            if (void.class == classType || Void.class == classType || Result.class.isAssignableFrom(classType)) {
                return null;
            }
            if (Object.class == classType) {
                return "<Any>";
            }
            try {
                if (classType.isEnum()) {
                    Object[] ea = classType.getEnumConstants();
                    int len = ea.length;
                    return 0 < len ? ea[N.randInt(len)] : null;
                } else if (Locale.class == classType) {
                    return (defLocale);
                } else if (String.class == classType) {
                    String mockValue = S.random(5);
                    if (spec.hasAnnotation(Sensitive.class)) {
                        return Act.crypto().encrypt(mockValue);
                    }
                    return S.random(5);
                } else if (classType.isArray()) {
                    Object sample = Array.newInstance(classType.getComponentType(), 2);
                    Array.set(sample, 0, generateSampleData(BeanSpec.of(classType.getComponentType(), Act.injector()), C.newSet(typeChain), C.newList(nameChain)));
                    Array.set(sample, 1, generateSampleData(BeanSpec.of(classType.getComponentType(), Act.injector()), C.newSet(typeChain), C.newList(nameChain)));
                    return sample;
                } else if ($.isSimpleType(classType)) {
                    if (Enum.class == classType) {
                        return "<Any Enum>";
                    }
                    if (!classType.isPrimitive()) {
                        classType = $.primitiveTypeOf(classType);
                    }
                    return StringValueResolver.predefined().get(classType).resolve(null);
                } else if (LocalDateTime.class.isAssignableFrom(classType)) {
                    return LocalDateTime.now();
                } else if (DateTime.class.isAssignableFrom(classType)) {
                    return DateTime.now();
                } else if (LocalDate.class.isAssignableFrom(classType)) {
                    return LocalDate.now();
                } else if (LocalTime.class.isAssignableFrom(classType)) {
                    return LocalTime.now();
                } else if (Date.class.isAssignableFrom(classType)) {
                    return new Date();
                } else if (classType.getName().contains(".ObjectId")) {
                    return "<id>";
                } else if (BigDecimal.class == classType) {
                    return BigDecimal.valueOf(1.1);
                } else if (BigInteger.class == classType) {
                    return BigInteger.valueOf(1);
                } else if (ISObject.class.isAssignableFrom(classType)) {
                    return null;
                } else if (Map.class.isAssignableFrom(classType)) {
                    Map map = $.cast(Act.getInstance(classType));
                    List<Type> typeParams = spec.typeParams();
                    if (typeParams.isEmpty()) {
                        typeParams = Generics.typeParamImplementations(classType, Map.class);
                    }
                    if (typeParams.size() < 2) {
                        map.put(S.random(), S.random());
                        map.put(S.random(), S.random());
                    } else {
                        Type keyType = typeParams.get(0);
                        Type valType = typeParams.get(1);
                        map.put(
                                generateSampleData(BeanSpec.of(keyType, null, Act.injector()), C.newSet(typeChain), C.newList(nameChain)),
                                generateSampleData(BeanSpec.of(valType, null, Act.injector()), C.newSet(typeChain), C.newList(nameChain)));
                        map.put(
                                generateSampleData(BeanSpec.of(keyType, null, Act.injector()), C.newSet(typeChain), C.newList(nameChain)),
                                generateSampleData(BeanSpec.of(valType, null, Act.injector()), C.newSet(typeChain), C.newList(nameChain)));
                    }
                } else if (Iterable.class.isAssignableFrom(classType)) {
                    Collection col = $.cast(Act.getInstance(classType));
                    List<Type> typeParams = spec.typeParams();
                    if (typeParams.isEmpty()) {
                        typeParams = Generics.typeParamImplementations(classType, Map.class);
                    }
                    if (typeParams.isEmpty()) {
                        col.add(S.random());
                    } else {
                        Type componentType = typeParams.get(0);
                        col.add(generateSampleData(BeanSpec.of(componentType, null, Act.injector()), C.newSet(typeChain), C.newList(nameChain)));
                        col.add(generateSampleData(BeanSpec.of(componentType, null, Act.injector()), C.newSet(typeChain), C.newList(nameChain)));
                    }
                    return col;
                }

                if (null != stringValueResolver(classType)) {
                    return S.random(5);
                }

                Object obj = Act.getInstance(classType);
                List<Field> fields = $.fieldsOf(classType);
                for (Field field : fields) {
                    if (Modifier.isStatic(field.getModifiers())) {
                        continue;
                    }
                    if (ParamValueLoaderService.shouldWaive(field)) {
                        continue;
                    }
                    Class<?> fieldType = field.getType();
                    Object val = null;
                    try {
                        field.setAccessible(true);
                        val = generateSampleData(BeanSpec.of(field, Act.injector()), C.newSet(typeChain), C.newList(nameChain));
                        Class<?> valType = null == val ? null : val.getClass();
                        if (null != valType && fieldType.isAssignableFrom(valType)) {
                            field.set(obj, val);
                        }
                    } catch (Exception e) {
                        LOGGER.warn("Error setting value[%s] to field[%s.%s]", val, classType.getSimpleName(), field.getName());
                    }
                }
                return obj;
            } catch (Exception e) {
                LOGGER.warn("error generating sample data for type: %s", classType);
                return null;
            }
        } finally {
            //typeChain.remove(classType);
        }
    }

    private static <T> StringValueResolver stringValueResolver(Class<? extends T> type) {
        return Act.app().resolverManager().resolver(type);
    }

}
