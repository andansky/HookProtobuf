package com.ss.android.ugc.bytex.hookproguard;

import com.android.build.gradle.AppExtension;
import com.android.build.gradle.AppPlugin;
import com.android.build.gradle.FeatureExtension;
import com.android.build.gradle.FeaturePlugin;
import com.android.build.gradle.LibraryExtension;
import com.android.build.gradle.LibraryPlugin;
import com.android.build.gradle.api.BaseVariant;
import com.ss.android.ugc.bytex.common.configuration.BooleanProperty;
import com.ss.android.ugc.bytex.common.graph.Graph;
import com.ss.android.ugc.bytex.common.graph.Node;
import com.ss.android.ugc.bytex.proguardconfigurationresolver.ProguardConfigurationResolver;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.execution.TaskExecutionListener;
import org.gradle.api.file.FileCollection;
import org.gradle.api.tasks.TaskState;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import proguard.ClassSpecification;
import proguard.Configuration;
import proguard.ConfigurationParser;
import proguard.KeepClassSpecification;
import proguard.MemberSpecification;
import proguard.util.ClassNameParser;
import proguard.util.ListParser;
import proguard.util.NameParser;

public class ProguardConfigurationAnalyzer {
    private static ProguardConfigurationAnalyzer INSTANCE;
    private static final MemberSpecification defaultMemberSpecification = new MemberSpecification();
    private Map<String, ProguardConfigurationWithVariantAnalyzer> analyzers = new HashMap<>();
    private Set<String> dependenciesRunBeforeTasks = new HashSet<>();
    private Map<String, AtomicInteger> runningVariantState = new HashMap<>();
    private List<String> runningVariants = new LinkedList<>();

    private ProguardConfigurationAnalyzer(Project project) {
        project.afterEvaluate(p -> {
            Set<String> variants = null;
            for (Plugin plugin : project.getPlugins()) {
                if (plugin instanceof FeaturePlugin) {
                    variants = project.getExtensions()
                            .getByType(FeatureExtension.class)
                            .getLibraryVariants()
                            .stream()
                            .map(BaseVariant::getName)
                            .collect(Collectors.toSet());
                } else if (plugin instanceof LibraryPlugin) {
                    variants = project.getExtensions()
                            .getByType(LibraryExtension.class)
                            .getLibraryVariants()
                            .stream()
                            .map(BaseVariant::getName)
                            .collect(Collectors.toSet());
                } else if (plugin instanceof AppPlugin) {
                    variants = project.getExtensions()
                            .getByType(AppExtension.class)
                            .getApplicationVariants()
                            .stream()
                            .map(BaseVariant::getName)
                            .collect(Collectors.toSet());
                }
            }
            if (variants == null) {
                throw new IllegalStateException("Plugin Only Valid in FeaturePlugin???LibraryPlugin and AppPlugin");
            }
            for (String variant : variants) {
                analyzers.put(variant,
                        new ProguardConfigurationWithVariantAnalyzer(project,
                                variant,
                                dependenciesRunBeforeTasks,
                                BooleanProperty.ENABLE_VERIFY_PROGUARD_CONFIGURATION_CHANGED.value()));
                runningVariantState.put(variant, new AtomicInteger(0));
            }
        });
        project.getGradle().getTaskGraph().addTaskExecutionListener(new TaskExecutionListener() {
            Map<String, String> capitals = new ConcurrentHashMap<>();

            @Override
            public void beforeExecute(@NotNull Task task) {
                for (Map.Entry<String, AtomicInteger> entry : runningVariantState.entrySet()) {
                    if (task.getName().contains(capital(entry.getKey()))) {
                        synchronized (this) {
                            if (entry.getValue().incrementAndGet() == 1) {
                                runningVariants.add(entry.getKey());
                            }
                        }
                    }
                }
            }

            @Override
            public void afterExecute(@NotNull Task task, @NotNull TaskState taskState) {
                for (Map.Entry<String, AtomicInteger> entry : runningVariantState.entrySet()) {
                    if (task.getName().contains(capital(entry.getKey()))) {
                        synchronized (this) {
                            if (entry.getValue().decrementAndGet() == 0) {
                                runningVariants.remove(entry.getKey());
                            }
                        }
                    }
                }
            }

            private String capital(String str) {
                return capitals.computeIfAbsent(str, s -> s.substring(0, 1).toUpperCase() + s.substring(1));
            }
        });
    }

    /**
     * ????????????Proguard??????????????????????????????proguard??????????????????????????????<br/>
     * ??????????????????gradle configuration????????????,?????????ByteX???plugin???onApply?????????.<br/>
     * ?????????Proguard?????????????????????????????????ByteX???task??????.???????????????ByteX?????????????????????????????????<br/>
     * ????????????{@link #hook(Project, String...)}
     */
    public static ProguardConfigurationAnalyzer hook(Project project) {
        return hook(project, "transformClassesAndResourcesWithByteXFor", "transformClassesWithByteXFor");
    }

    /**
     * ????????????Proguard??????????????????????????????proguard??????????????????????????????<br/>
     * ??????????????????gradle configuration????????????,?????????ByteX???plugin???onApply?????????.<br/>
     * ?????????Proguard????????????????????????????????????????????????task????????????????????????????????????????????????.<br/>
     * ??????:??????app?????????module????????????consumerProguardFiles????????????merge${variantName}ConsumerProguardFiles<br/>
     * task??????proguard??????????????????bytex??????????????????task?????????????????????bytex????????????????????????????????????.<br/>
     *
     * @param taskNames ?????????merge${variantName}ConsumerProguardFiles?????????task???????????????taskname,????????????variantName?????????transformClassesAndResourcesWithByteXFor
     */
    public static ProguardConfigurationAnalyzer hook(Project project, String... taskNames) {
        if (INSTANCE == null) {
            synchronized (ProguardConfigurationAnalyzer.class) {
                if (INSTANCE == null) {
                    INSTANCE = new ProguardConfigurationAnalyzer(project);
                    project.getGradle().buildFinished(p -> INSTANCE = null);
                }
            }
        }
        INSTANCE.dependenciesRunBeforeTasks.addAll(Arrays.asList(taskNames));
        return INSTANCE;
    }

    /**
     * ??????task????????????????????????????????????Proguard??????????????????
     *
     * @throws IllegalStateException :??????task??????????????????????????????
     */
    @Deprecated
    public boolean isValid() {
        return isValid(getAndCheckSingleVariant());
    }

    /**
     * ????????????????????????????????????Proguard??????????????????
     *
     * @throws NullPointerException :?????????????????????
     */
    public boolean isValid(String variant) {
        return analyzers.get(variant).isValid();
    }

    /**
     * ??????task????????????????????????proguard?????????????????????
     *
     * @throws IllegalStateException :??????task??????????????????????????????
     */
    @Deprecated
    public void prepare() {
        prepare(getAndCheckSingleVariant());
    }

    /**
     * ??????????????????????????????proguard?????????????????????
     *
     * @throws NullPointerException :?????????????????????
     */
    public void prepare(String variant) {
        analyzers.get(variant).prepare();
    }

    /**
     * ??????task???????????????????????????????????????keep???????????????????????????
     *
     * @throws IllegalStateException :??????task??????????????????????????????
     */
    @Deprecated
    public boolean shouldKeep(ClassInfo classInfo) {
        return shouldKeep(getAndCheckSingleVariant(), classInfo);
    }

    /**
     * ???????????????????????????????????????keep???????????????????????????
     *
     * @throws NullPointerException :?????????????????????
     */
    public boolean shouldKeep(String variant, ClassInfo classInfo) {
        return analyzers.get(variant).shouldKeep(classInfo);
    }

    /**
     * ??????task???????????????????????????????????????keep???????????????????????????
     *
     * @throws IllegalStateException :??????task??????????????????????????????
     */
    @Deprecated
    public boolean shouldKeep(ClassInfo classInfo, MethodInfo methodInfo) {
        return shouldKeep(getAndCheckSingleVariant(), classInfo, methodInfo);
    }

    /**
     * ???????????????????????????????????????keep???????????????????????????
     *
     * @throws NullPointerException :?????????????????????
     */
    public boolean shouldKeep(String variant, ClassInfo classInfo, MethodInfo methodInfo) {
        return analyzers.get(variant).shouldKeep(classInfo, methodInfo);
    }

    /**
     * ??????task???????????????????????????????????????keep???????????????????????????
     *
     * @throws IllegalStateException :??????task??????????????????????????????
     */
    @Deprecated
    public boolean shouldKeep(Graph graph, MethodInfo methodInfo) {
        return shouldKeep(getAndCheckSingleVariant(), graph, methodInfo);
    }

    /**
     * ???????????????????????????????????????keep???????????????????????????
     *
     * @throws NullPointerException :?????????????????????
     */
    public boolean shouldKeep(String variant, Graph graph, MethodInfo methodInfo) {
        return analyzers.get(variant).shouldKeep(graph, methodInfo);
    }

    /**
     * ??????task???????????????????????????????????????keep???????????????????????????
     *
     * @throws IllegalStateException :??????task??????????????????????????????
     */
    @Deprecated
    public boolean shouldKeep(Graph graph, ClassInfo classInfo) {
        return shouldKeep(getAndCheckSingleVariant(), graph, classInfo);
    }

    /**
     * ???????????????????????????????????????keep???????????????????????????
     *
     * @throws NullPointerException :?????????????????????
     */
    public boolean shouldKeep(String variant, Graph graph, ClassInfo classInfo) {
        return analyzers.get(variant).shouldKeep(graph, classInfo);
    }

    private String getAndCheckSingleVariant() {
        if (runningVariants.size() == 1) {
            return runningVariants.get(0);
        }
        throw new IllegalStateException();
    }

    private static class ProguardConfigurationWithVariantAnalyzer {
        private Configuration configuration;
        private ListParser classNameParser = new ListParser(new ClassNameParser());
        private ListParser nameParser = new ListParser(new NameParser());
        // keep the whole class. ??????????????????keep????????????
        private final List<KeepClassSpecificationHolder> wholeClassSpecifications = new ArrayList<>();
        // keep some specific methods in a class. ?????????????????????keep??????????????????????????????
        private final List<KeepClassSpecificationHolder> classSpecificationsForMethod = new ArrayList<>();
        // specification with the class hierarchy
        private final List<KeepClassSpecificationHolder> classHierarchySpecifications = new ArrayList<>();
        private final Map<String, Long> configurationFiles = new HashMap<>();
        private final ProguardConfigurationResolver configurationResolver;
        private boolean valid = false;

        ProguardConfigurationWithVariantAnalyzer(Project project, String variantName, Set<String> dependenciesRunBeforeTasks, boolean verify) {
            super();
            configurationResolver = ProguardConfigurationResolverFactory.createProguardConfigurationResolver(project, variantName);
            Task hookTask = configurationResolver.getTask();
            if (hookTask != null) {
                String capitalVariantName = variantName.substring(0, 1).toUpperCase() + variantName.substring(1);
                dependenciesRunBeforeTasks.stream()
                        .map(s -> hookTask.getProject().getTasks().findByName(s + capitalVariantName))
                        .filter(Objects::nonNull)
                        .forEach(t -> t.dependsOn(configurationResolver.getAllConfigurationFiles()));
                if (verify) {
                    hookTask.doFirst(task1 -> verifyProguardConfiguration());
                    hookTask.doLast(task12 -> verifyProguardConfiguration());
                }
                project.getGradle()
                        .getTaskGraph()
                        .whenReady(taskExecutionGraph -> valid = taskExecutionGraph.hasTask(hookTask));
            }
        }

        boolean isValid() {
            return valid;
        }

        private void verifyProguardConfiguration() {
            if (configuration == null) {
                return;
            }
            List<File> files = getAllConfigurationFiles();
            boolean matchAll = true;
            if (files.size() == configurationFiles.size()) {
                for (File file : files) {
                    if (configurationFiles.get(file.getAbsolutePath()) != (file.exists() ? file.length() : -1L)) {
                        matchAll = false;
                        break;
                    }
                }
            } else {
                matchAll = false;
            }
            if (!matchAll) {
                StringBuilder errMsg = new StringBuilder();
                errMsg.append("Proguard Configuration Files:\n");
                files.stream()
                        .map(file -> file.getAbsolutePath() + ":" + (file.exists() ? file.length() : -1L))
                        .sorted()
                        .forEach(s -> errMsg.append(s).append("\n"));
                errMsg.append("Parsed Configuration Files:\n");
                configurationFiles.entrySet().stream()
                        .map(stringLongEntry -> stringLongEntry.getKey() + ":" + stringLongEntry.getValue())
                        .sorted()
                        .forEach(s -> errMsg.append(s).append("\n"));
                throw new IllegalStateException(errMsg.toString());
            }
        }

        synchronized void prepare() {
            if (configuration != null) return;
            try {
                configuration = new Configuration();
                for (File file : getAllConfigurationFiles()) {
                    configurationFiles.put(file.getAbsolutePath(), file.exists() ? file.length() : -1L);
                    if (file.exists()) {
                        System.out.println("[ProguardConfigurationAnalyzer] proguard configuration file : " + file.getAbsolutePath());
                        ConfigurationParser parser = new ConfigurationParser(file, System.getProperties());
                        try {
                            parser.parse(configuration);
                        } finally {
                            parser.close();
                        }
                    }
                }
                filterAndSplit(configuration);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        private List<File> getAllConfigurationFiles() {
            FileCollection allConfigurationFiles = configurationResolver.getAllConfigurationFiles();
            if (allConfigurationFiles == null) {
                return Collections.emptyList();
            }
            List<File> allFiles = new LinkedList<>();
            for (File file : allConfigurationFiles) {
                allFiles.add(file);
            }
            return allFiles;
        }

        private void filterAndSplit(Configuration configuration) {
            if (configuration.keep == null) return;
            List<KeepClassSpecification> newKeepList = new ArrayList<>();
            for (KeepClassSpecification specification : (List<KeepClassSpecification>) configuration.keep) {
                if (specification.allowObfuscation || specification.allowOptimization || specification.allowShrinking) {
                    continue;
                }
                if (specification.methodSpecifications == null || specification.methodSpecifications.isEmpty()) {
                    continue;
                }
                boolean specificationOnlyForInitializationMethod = true;
                List<MemberSpecification> methodList = specification.methodSpecifications;
                for (MemberSpecification memberSpecification : methodList) {
                    if (!"<init>".equals(memberSpecification.name)) {
                        specificationOnlyForInitializationMethod = false;
                        break;
                    }
                }
                if (specificationOnlyForInitializationMethod) {
                    continue;
                }

                KeepClassSpecificationHolder specificationHolder = new KeepClassSpecificationHolder(specification, classNameParser);
                if (isSpecificationMatchAllMethods(methodList)) {
                    wholeClassSpecifications.add(specificationHolder);
                } else {
                    specificationHolder.parserMethodSpecifications(nameParser);
                    classSpecificationsForMethod.add(specificationHolder);
                }
                if (specification.extendsClassName != null) {
                    classHierarchySpecifications.add(specificationHolder);
                }
                newKeepList.add(specification);
            }
            configuration.keep = newKeepList;
        }

        private boolean isSpecificationMatchAllMethods(List<MemberSpecification> methodList) {
            return methodList.size() == 1 && defaultMemberSpecification.equals(methodList.get(0));
        }

        boolean shouldKeep(ClassInfo classInfo) {
            for (KeepClassSpecificationHolder specification : wholeClassSpecifications) {
                KeepClassSpecification realSpecification = specification.getInstance();
                if (realSpecification.className != null && !specification.match(classInfo.getName()))
                    continue;
                if (realSpecification.extendsClassName != null) {
                    if (!matchExtendsClass(classInfo, realSpecification)) continue;
                }
                if (realSpecification.annotationType != null) {
                    if (!matchAnnotation(classInfo, realSpecification)) continue;
                }
                if (realSpecification.requiredSetAccessFlags != 0) {
                    if (!matchSetAccessFlags(classInfo, realSpecification)) continue;
                }
                if (realSpecification.requiredUnsetAccessFlags != 0) {
                    if (!matchUnSetAccessFlags(classInfo, realSpecification)) continue;
                }
                return true;
            }
            return false;
        }

        private boolean matchExtendsClass(ClassInfo classInfo, KeepClassSpecification realSpecification) {
            boolean match = false;
            if (realSpecification.extendsClassName.equals(classInfo.getSuperName()))
                match = true;
            if (!match && classInfo.getInterfaces() != null) {
                for (String itf : classInfo.getInterfaces()) {
                    if (realSpecification.extendsClassName.equals(itf)) {
                        match = true;
                        break;
                    }
                }
            }
            return match;
        }

        private boolean matchAnnotation(ClassInfo classInfo, KeepClassSpecification realSpecification) {
            if (classInfo.getAnnotations() != null) {
                for (String annotation : classInfo.getAnnotations()) {
                    if (realSpecification.annotationType.equals(annotation)) return true;
                }
            }
            return false;
        }

        boolean shouldKeep(ClassInfo classInfo, MethodInfo methodInfo) {
            for (KeepClassSpecificationHolder specification : classSpecificationsForMethod) {
                KeepClassSpecification realSpecification = specification.getInstance();
                if (realSpecification.className != null && !specification.match(classInfo.getName()))
                    continue;
                if (realSpecification.extendsClassName != null) {
                    if (!matchExtendsClass(classInfo, realSpecification)) continue;
                }
                if (realSpecification.annotationType != null) {
                    if (!matchAnnotation(classInfo, realSpecification)) continue;
                }
                List<MemberSpecificationHolder> methodSpecifications = specification.getMethodSpecifications(nameParser);
                if (methodSpecifications != null) {
                    if (!matchMethod(methodInfo, methodSpecifications)) continue;
                }
                return true;
            }
            return false;
        }

        private boolean matchMethod(MethodInfo methodInfo, List<MemberSpecificationHolder> methodSpecifications) {
            for (MemberSpecificationHolder specification : methodSpecifications) {
                MemberSpecification realSpecification = specification.getInstance();
                if (realSpecification.annotationType != null) {
                    if (!matchAnnotation(methodInfo, realSpecification)) continue;
                }
                if (realSpecification.requiredSetAccessFlags != 0) {
                    if (!matchSetAccessFlags(methodInfo, realSpecification)) continue;
                }
                if (realSpecification.requiredUnsetAccessFlags != 0) {
                    if (!matchUnSetAccessFlags(methodInfo, realSpecification)) continue;
                }
                if (!specification.match(methodInfo.getName(), methodInfo.getDesc())) {
                    continue;
                }
                return true;
            }
            return false;
        }

        private boolean matchSetAccessFlags(MethodInfo methodInfo, MemberSpecification realSpecification) {
            return (methodInfo.getAccess() & realSpecification.requiredSetAccessFlags) == realSpecification.requiredSetAccessFlags;
        }

        private boolean matchUnSetAccessFlags(MethodInfo methodInfo, MemberSpecification realSpecification) {
            return (methodInfo.getAccess() & realSpecification.requiredUnsetAccessFlags) == 0;
        }

        private boolean matchSetAccessFlags(ClassInfo classInfo, ClassSpecification realSpecification) {
            return (classInfo.getAccess() & realSpecification.requiredSetAccessFlags) == realSpecification.requiredSetAccessFlags;
        }

        private boolean matchUnSetAccessFlags(ClassInfo classInfo, ClassSpecification realSpecification) {
            return (classInfo.getAccess() & realSpecification.requiredUnsetAccessFlags) == 0;
        }


        private boolean matchAnnotation(MethodInfo methodInfo, MemberSpecification realSpecification) {
            if (methodInfo.getAnnotations() != null) {
                for (String annotation : methodInfo.getAnnotations()) {
                    if (realSpecification.annotationType.equals(annotation)) return true;
                }
            }
            return false;
        }


        boolean shouldKeep(Graph graph, MethodInfo methodInfo) {
            for (KeepClassSpecificationHolder specification : classHierarchySpecifications) {
                KeepClassSpecification realSpecification = specification.getInstance();
                if (realSpecification.className != null && !specification.match(methodInfo.getClassInfo().getName()))
                    continue;
                if (realSpecification.extendsClassName != null) {
                    if (!matchExtendsClass(graph, methodInfo.getClassInfo().getName(), specification))
                        continue;
                }
                if (realSpecification.annotationType != null) {
                    if (!matchAnnotation(methodInfo.getClassInfo(), realSpecification)) continue;
                }
                List<MemberSpecificationHolder> methodSpecifications = specification.getMethodSpecifications(nameParser);
                if (methodSpecifications != null) {
                    if (!matchMethod(methodInfo, methodSpecifications)) continue;
                }
                return true;
            }
            return false;
        }

        boolean shouldKeep(Graph graph, ClassInfo classInfo) {
            for (KeepClassSpecificationHolder specification : classHierarchySpecifications) {
                KeepClassSpecification realSpecification = specification.getInstance();
                if (realSpecification.className != null && !specification.match(classInfo.name))
                    continue;
                if (realSpecification.extendsClassName != null) {
                    if (!matchExtendsClass(graph, classInfo.name, specification))
                        continue;
                }
                if (realSpecification.annotationType != null) {
                    if (!matchAnnotation(classInfo, realSpecification)) continue;
                }
                return true;
            }
            return false;
        }

        private boolean matchExtendsClass(Graph graph, String className, KeepClassSpecificationHolder specification) {
            Node node = specification.computeExtendsClassNode(graph);
            if (node == null) return false;
            Node derivedClass = graph.get(className);
            return derivedClass != null && derivedClass.inheritFrom(node);
        }
    }
}
