package com.ss.android.ugc.bytex.access_inline;


import com.android.build.gradle.AppExtension;
import com.ss.android.ugc.bytex.common.BaseContext;
import com.ss.android.ugc.bytex.common.graph.ClassEntity;
import com.ss.android.ugc.bytex.common.graph.ClassNode;
import com.ss.android.ugc.bytex.common.graph.FieldEntity;
import com.ss.android.ugc.bytex.common.graph.Graph;
import com.ss.android.ugc.bytex.common.graph.MemberEntity;
import com.ss.android.ugc.bytex.common.graph.MemberType;
import com.ss.android.ugc.bytex.common.graph.MethodEntity;
import com.ss.android.ugc.bytex.common.graph.Node;
import com.ss.android.ugc.bytex.common.graph.RefMemberEntity;
import com.ss.android.ugc.bytex.common.utils.TypeUtil;
import com.ss.android.ugc.bytex.access_inline.visitor.Access$MethodEntity;
import com.ss.android.ugc.bytex.access_inline.visitor.RefFieldEntity;
import com.ss.android.ugc.bytex.access_inline.visitor.RefMethodEntity;

import org.gradle.api.Project;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public class Context extends BaseContext<AccessInlineExtension> {
    private static final String SEPARATOR = "#";

    private static String getKey(String owner, String name, String desc) {
        return owner + SEPARATOR + name + SEPARATOR + desc;
    }

    // key is the access$ method identity, value is the access$ method code.
    private final Map<String, Access$MethodEntity> access$Methods = new ConcurrentHashMap<>(512);

    private final Map<String, RefMemberEntity> accessedMembers = new ConcurrentHashMap<>(512);

    private final List<Pattern> whiteList = new ArrayList<>();

    private Graph graph;

    @Override
    public synchronized void init() {
        super.init();
        whiteList.clear();
        for (String s : extension.getWhiteList()) {
            whiteList.add(Pattern.compile(s));
        }
    }

    public boolean inWhiteList(String className) {
        for (Pattern pattern : whiteList) {
            if (pattern.matcher(className).matches()) {
                return true;
            }
        }
        return false;
    }

    public Access$MethodEntity addAccess$Method(String owner, String name, String desc) {
        Access$MethodEntity entity = new Access$MethodEntity(owner, name, desc);
        access$Methods.put(getKey(owner, name, desc), entity);
        return entity;
    }

    public Access$MethodEntity addAccess$Method(Access$MethodEntity method) {
        return access$Methods.put(getKey(method.className(), method.name(), method.desc()), method);
    }

    public synchronized RefMemberEntity addAccessedMembers(String owner, String name, String desc, boolean isField) {
        String targetKey = getKey(owner, name, desc);
        RefMemberEntity target = accessedMembers.get(targetKey);
        if (target == null) {
            if (isField) {
                getLogger().d("FoundAccessField", String.format("Found access$ method target field( owner = [%s], name = [%s], desc = [%s] )", owner, name, desc));
                target = new RefFieldEntity(new FieldEntity(MemberEntity.ACCESS_UNKNOWN, owner, name, desc));
            } else {
                getLogger().d("FoundAccessMethod", String.format("Found access$ method target method( owner = [%s], name = [%s], desc = [%s] )", owner, name, desc));
                target = new RefMethodEntity(new MethodEntity(MemberEntity.ACCESS_UNKNOWN, owner, name, desc));
            }
            accessedMembers.put(targetKey, target);
        }
        return target;
    }

    public Access$MethodEntity getAccess$Method(String owner, String name, String desc) {
        return access$Methods.get(getKey(owner, name, desc));
    }

    public int methodCount() {
        return access$Methods.size();
    }

    /**
     * access????????????????????????????????????
     * 1. ????????????filed???method???private???
     * 2. ????????????filed???method?????????package?????????????????????protected??????????????????????????????????????????package???????????????protected?????? ????????????package??????protected??????????????????????????????protected??????????????????package??????????????????
     * 3. super???????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????
     * ps: lancet????????????????????????????????????access??????
     *
     * Why access methods would be generated?
     * 1. access private fields or methods.
     * 2. access protected fields or methods, which are at other packages.
     */
    public void prepare() {
        if (graph == null) {
            graph = getClassGraph();
            // ???????????????accessedMembers?????????????????? To confirm each accessedMembers access attribute.
            for (Map.Entry<String, Access$MethodEntity> entry : access$Methods.entrySet()) {
                Access$MethodEntity entity = entry.getValue();
                RefMemberEntity target = entity.getTarget();
                String targetKey = getKey(target.className(), target.name(), target.desc());
                try {
                    List<MemberEntity> overrideMembers;
                    try {
                        overrideMembers = confirmAccess(target);
                        String oldTargetKey = targetKey;
                        targetKey = getKey(target.className(), target.name(), target.desc());
                        if (TypeUtil.isPublic(target.access())) {
                            accessedMembers.remove(oldTargetKey);
                            accessedMembers.remove(targetKey);
                            getLogger().d("AccessMethod", String.format("Access$ method : className = [%s], methodName = [%s], desc = [%s] access to public method or field. Target %s (owner = [%s], name = [%s], desc = [%s])",
                                    entity.className(), entity.name(), entity.desc(), target.type() == MemberType.FIELD ? FieldEntity.class.getSimpleName() : MethodEntity.class.getSimpleName(),
                                    target.className(), target.name(), target.desc()));
                        } else if (!targetKey.equals(oldTargetKey)) { // change class! Target method or field is at super class.
                            accessedMembers.remove(oldTargetKey);
                            RefMemberEntity realTarget = accessedMembers.get(targetKey);
                            if (realTarget == null) {
                                accessedMembers.put(targetKey, target);
                            } else {
                                realTarget.setAccess(target.access());
                                target = realTarget;
                            }
                        }
                    } finally {
                        target.inc();
                    }
                    FieldInsnNode fieldInsn = entity.getFieldInsn();
                    if (fieldInsn != null) {
                        // check access$method parameters
                        if (fieldInsn.getOpcode() == Opcodes.GETSTATIC) {
                            if (TypeUtil.getParameterCountFromMethodDesc(entity.desc()) != 0) {
                                throw new ShouldSkipInlineException("Access$ method with GETSTATIC has abnormal count of parameter.");
                            }
                        } else if (fieldInsn.getOpcode() == Opcodes.PUTSTATIC) {
                            if (TypeUtil.getParameterCountFromMethodDesc(entity.desc()) != 1) {
                                throw new ShouldSkipInlineException("Access$ method with PUTSTATIC has abnormal count of parameter.");
                            }
                        }
                    }
                    MethodInsnNode methodInsn = entity.getMethodInsn();
                    if (methodInsn != null) {
                        if (methodInsn.getOpcode() == Opcodes.INVOKESPECIAL) {
                            if (TypeUtil.isPrivate(target.access())) {
                                // invoke private method, change to INVOKEVIRTUAL instead of INVOKESPECIAL
                                methodInsn.setOpcode(Opcodes.INVOKEVIRTUAL);
                            } else {
                                // Skip the super invoke...
                                throw new ShouldSkipInlineException("Access$ method to super field or method should be skip.");
                            }
                        } else if (methodInsn.getOpcode() == Opcodes.INVOKESTATIC) {
                            if (TypeUtil.getParameterCountFromMethodDesc(entity.desc()) != TypeUtil.getParameterCountFromMethodDesc(target.desc())) {
                                throw new ShouldSkipInlineException("Access$ method with INVOKESTATIC has abnormal count of parameter.");
                            }
                        }
                    }
                    if (overrideMembers != null) {
                        overrideMembers.forEach(m -> {
                            String key = getKey(m.className(), m.name(), m.desc());
                            RefMemberEntity existMember = accessedMembers.get(key);
                            if (existMember == null) {
                                existMember = newRefMember(m);
                                accessedMembers.put(key, existMember);
                            }
                            existMember.inc();
                        });
                    }
                } catch (ShouldSkipInlineException e) {
                    access$Methods.remove(entry.getKey());
                    target.dec();
                    if (target.isFree()) {
                        accessedMembers.remove(targetKey);
                        getLogger().d("SkipInlineAccess", String.format("Skip inline access to %s (owner = [%s], name = [%s], desc = [%s]), for the reason that %s",
                                target.type() == MemberType.FIELD ? FieldEntity.class.getSimpleName() : MethodEntity.class.getSimpleName(),
                                target.className(), target.name(), target.desc(), e.reason));
                    }
                }
            }
        }
    }

    private List<MemberEntity> confirmAccess(MemberEntity target) {
        List<MemberEntity> accessShouldBeChangeList = new ArrayList<>();
        Node classNode = graph.get(target.className());
        // backtrace to super
        graph.backtrackToParent((ClassNode) classNode, node -> {
            ClassEntity classEntity = node.entity;
            List<? extends MemberEntity> members = target.type() == MemberType.METHOD ? classEntity.methods : classEntity.fields;
            for (MemberEntity m : members) {
                if (target.name().equals(m.name()) && target.desc().equals(m.desc())) {
                    // found it!
                    // if the class in android.jar, there is no way to change the access.
                    if (!classEntity.fromAndroid) {
                        target.setAccess(m.access());
                        target.setClassName(m.className());
                        // forwards to children to find override methods
                        if (m.type() == MemberType.METHOD && !TypeUtil.isStatic(m.access())) {
                            if (TypeUtil.isPrivate(m.access())) {
                                if (node.parent != null) {
                                    graph.backtrackToParent(node.parent, node_ -> {
                                        ClassEntity classEntity_ = node_.entity;
                                        for (MethodEntity m_ : classEntity_.methods) {
                                            if (target.name().equals(m_.name()) && target.desc().equals(m_.desc())
                                                    && !TypeUtil.isStatic(m_.access()) && TypeUtil.isPackage(m_.access())) {
                                                throw new ShouldSkipInlineException("Parent class has the method with same name and package access.");
                                            }
                                        }
                                        return false;
                                    });
                                }
                                graph.traverseAllChild(node, node_ -> {
                                    ClassEntity classEntity_ = node_.entity;
                                    for (MethodEntity m_ : classEntity_.methods) {
                                        if (target.name().equals(m_.name()) && target.desc().equals(m_.desc())
                                                && !TypeUtil.isStatic(m_.access())) {
                                            throw new ShouldSkipInlineException("Child classes have the method with same name.");
                                        }
                                    }
                                });
                            } else if (TypeUtil.isProtected(m.access())) {
                                if (!TypeUtil.isFinal(m.access())) {
                                    graph.traverseAllChild(node, node_ -> {
                                        ClassEntity classEntity_ = node_.entity;
                                        for (MethodEntity m_ : classEntity_.methods) {
                                            if (m_.name().equals(target.name()) && m_.desc().equals(target.desc())) {
                                                if (!TypeUtil.isStatic(m_.access()) && TypeUtil.isProtected(m_.access()))
                                                    accessShouldBeChangeList.add(m_);
                                                break;
                                            }
                                        }
                                    });
                                }
                            } else if (!TypeUtil.isPublic(m.access())) {
                                // throw exception
                                throw new ShouldSkipInlineException("Method access is unknown.");
                            }
                        }
                    } else if (TypeUtil.isPublic(m.access())) {
                        target.setAccess(m.access());
                        target.setClassName(m.className());
                    } else {
                        throw new ShouldSkipInlineException("Access class is in android.jar and not public access.");
                    }
                    return true;
                }
            }
            return false;
        });
        return accessShouldBeChangeList;
    }

    private RefMemberEntity newRefMember(MemberEntity m) {
        if (m.type() == MemberType.FIELD) {
            return new RefFieldEntity((FieldEntity) m);
        } else if (m.type() == MemberType.METHOD) {
            return new RefMethodEntity((MethodEntity) m);
        }
        throw new RuntimeException("No such MemberEntity subtype");
    }

    public boolean isAccessedMember(String owner, String name, String desc) {
        return accessedMembers.containsKey(getKey(owner, name, desc));
    }

    public boolean isAccess$Method(String owner, String name, String desc) {
        return access$Methods.containsKey(getKey(owner, name, desc));
    }

    public boolean isPrivateAccessedMember(String owner, String name, String desc) {
        MemberEntity entity = accessedMembers.get(getKey(owner, name, desc));
        return entity != null && TypeUtil.isPrivate(entity.access());
    }

    public Context(Project project, AccessInlineExtension accessInlineExtension, AppExtension android) {
        super(project, android, accessInlineExtension);
    }

    @Override
    public void releaseContext() {
        super.releaseContext();
        access$Methods.clear();
        accessedMembers.clear();
        whiteList.clear();
        graph = null;
    }
}
