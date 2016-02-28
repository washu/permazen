
/*
 * Copyright (C) 2015 Archie L. Cobbs. All rights reserved.
 */

package org.jsimpledb.parse.expr;

import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

import java.beans.BeanInfo;
import java.beans.IndexedPropertyDescriptor;
import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;

import org.dellroad.stuff.java.Primitive;
import org.jsimpledb.JField;
import org.jsimpledb.JObject;
import org.jsimpledb.JSimpleField;
import org.jsimpledb.core.ObjId;
import org.jsimpledb.core.SimpleField;
import org.jsimpledb.parse.ParseContext;
import org.jsimpledb.parse.ParseException;
import org.jsimpledb.parse.ParseSession;
import org.jsimpledb.parse.ParseUtil;
import org.jsimpledb.parse.Parser;
import org.jsimpledb.parse.SpaceParser;
import org.jsimpledb.parse.func.AbstractFunction;
import org.jsimpledb.parse.util.AddSuffixFunction;

/**
 * Parses basic Java expressions such as {@link Class} literals, {@code new} expressions, auto-increment expressions,
 * array access, field access, method invocation, etc.
 *
 * <p>
 * Includes these extensions:
 * <ul>
 *  <li>Access to Java bean methods via "property syntax", e.g., {@code foo.name = "fred"} means {@code foo.setName("fred")}</li>
 *  <li>Access database object fields via "property syntax" given an object ID, e.g., <code>@fc21bf6d8930a215.name</code></li>
 *  <li>Array syntax for {@link java.util.Map} and {@link java.util.List} value access, e.g., {@code mymap[key] = value} means
 *      {@code mymap.put(key, value)}, and {@code mylist[12] = "abc"} means {@code mylist.set(12, "abc")}</li>
 * </ul>
 */
public class BaseExprParser implements Parser<Node> {

    public static final BaseExprParser INSTANCE = new BaseExprParser();

    private final SpaceParser spaceParser = new SpaceParser();

    @Override
    public Node parse(ParseSession session, ParseContext ctx, boolean complete) {

        // Parse initial atom
        Node node = new AtomParser(
          Iterables.transform(session.getFunctions().keySet(), new AddSuffixFunction("("))).parse(session, ctx, complete);

        // Handle new
        if (node instanceof IdentNode && ((IdentNode)node).getName().equals("new")) {

            // Get class name (or array type base), which must be a sequence of identifiers connected via dots
            new SpaceParser(true).parse(ctx, complete);
            final Matcher firstMatcher = ctx.tryPattern(IdentNode.NAME_PATTERN);
            if (firstMatcher == null)
                throw new ParseException(ctx, "expected class name");
            String className = firstMatcher.group();
            while (true) {
                final Matcher matcher = ctx.tryPattern("\\s*\\.\\s*(" + IdentNode.NAME_PATTERN + ")");
                if (matcher == null)
                    break;
                className += "." + matcher.group(1);
            }

            // Get array dimensions, if any
            final ArrayList<Node> dims = new ArrayList<>();
            boolean sawNull = false;
            while (true) {
                ctx.skipWhitespace();
                if (!ctx.tryLiteral("["))
                    break;
                ctx.skipWhitespace();
                if (ctx.tryLiteral("]")) {
                    dims.add(null);
                    sawNull = true;
                    continue;
                }
                if (!sawNull) {
                    dims.add(ExprParser.INSTANCE.parse(session, ctx, complete));
                    ctx.skipWhitespace();
                }
                if (!ctx.tryLiteral("]"))
                    throw new ParseException(ctx, "expected `]'").addCompletion("]");
            }

            // Resolve (base) class name
            final Class<?> baseClass;
            final Primitive<?> primitive;
            if (!dims.isEmpty() && (primitive = Primitive.forName(className)) != null) {
                if (primitive == Primitive.VOID)
                    throw new ParseException(ctx, "illegal instantiation of void array");
                baseClass = primitive.getType();
            } else {
                baseClass = session.resolveClass(className);
                if (baseClass == null)
                    throw new ParseException(ctx, "unknown class `" + className + "'");     // TODO: tab-completions
            }

            // Handle non-array
            if (dims.isEmpty()) {

                // Parse parameters
                if (!ctx.tryLiteral("("))
                    throw new ParseException(ctx, "expected `('").addCompletion("(");
                ctx.skipWhitespace();
                final List<Node> paramNodes = BaseExprParser.parseParams(session, ctx, complete);

                // Return constructor invocation node
                node = new Node() {
                    @Override
                    public Value evaluate(final ParseSession session) {
                        if (baseClass.isInterface())
                            throw new EvalException("invalid instantiation of " + baseClass);
                        final Object[] params = Lists.transform(paramNodes, new com.google.common.base.Function<Node, Object>() {
                            @Override
                            public Object apply(Node param) {
                                return param.evaluate(session).get(session);
                            }
                        }).toArray();
                        for (Constructor<?> constructor : baseClass.getConstructors()) {
                            final Class<?>[] ptypes = constructor.getParameterTypes();
                            if (ptypes.length != params.length)
                                continue;
                            try {
                                return new ConstValue(constructor.newInstance(params));
                            } catch (IllegalArgumentException e) {
                                continue;                               // wrong method, a parameter type didn't match
                            } catch (Exception e) {
                                final Throwable t = e instanceof InvocationTargetException ?
                                  ((InvocationTargetException)e).getTargetException() : e;
                                throw new EvalException("error invoking constructor `" + baseClass.getName() + "()': " + t, t);
                            }
                        }
                        throw new EvalException("no compatible constructor found in " + baseClass);
                    }
                };
            } else {                                        // handle array

                // Check number of dimensions
                if (dims.size() > 255)
                    throw new ParseException(ctx, "too many array dimensions (" + dims.size() + " > 255)");

                // Array literal must be present if and only if no dimensions are given
                final List<?> literal = dims.get(0) == null ?
                  this.parseArrayLiteral(session, ctx, complete, this.getArrayClass(baseClass, dims.size() - 1)) : null;

                // Return array instantiation invocation node
                node = new Node() {
                    @Override
                    public Value evaluate(final ParseSession session) {
                        final Class<?> elemType = BaseExprParser.this.getArrayClass(baseClass, dims.size() - 1);
                        return new ConstValue(literal != null ?
                          this.createLiteral(session, elemType, literal) : this.createEmpty(session, elemType, dims));
                    }

                    private Object createEmpty(ParseSession session, Class<?> elemType, List<Node> dims) {
                        final int length = dims.get(0).evaluate(session).checkIntegral(session, "array creation");
                        final Object array = Array.newInstance(elemType, length);
                        final List<Node> remainingDims = dims.subList(1, dims.size());
                        if (!remainingDims.isEmpty() && remainingDims.get(0) != null) {
                            for (int i = 0; i < length; i++)
                                Array.set(array, i, this.createEmpty(session, elemType.getComponentType(), remainingDims));
                        }
                        return array;
                    }

                    private Object createLiteral(ParseSession session, Class<?> elemType, List<?> values) {
                        final int length = values.size();
                        final Object array = Array.newInstance(elemType, length);
                        for (int i = 0; i < length; i++) {
                            if (elemType.isArray()) {
                                Array.set(array, i, this.createLiteral(session,
                                  elemType.getComponentType(), (List<?>)values.get(i)));
                            } else {
                                try {
                                    Array.set(array, i, ((Node)values.get(i)).evaluate(session).get(session));
                                } catch (Exception e) {
                                    throw new EvalException("error setting array value: " + e, e);
                                }
                            }
                        }
                        return array;
                    }
                };
            }
        }

        // Repeatedly parse operators (this gives left-to-right association)
        while (true) {

            // Parse operator, if any (one of `[', `.', `(', `++', or `--')
            final Matcher opMatcher = ctx.tryPattern("\\s*(\\[|\\.|\\(|\\+{2}|-{2})");
            if (opMatcher == null)
                return node;
            final String opsym = opMatcher.group(1);
            final int mark = ctx.getIndex();

            // Handle operators
            switch (opsym) {
            case "(":
            {
                // Atom must be an identifier, the name of a global function being invoked
                if (!(node instanceof IdentNode))
                    throw new ParseException(ctx);
                final String functionName = ((IdentNode)node).getName();
                final AbstractFunction function = session.getFunctions().get(functionName);
                if (function == null) {
                    throw new ParseException(ctx, "unknown function `" + functionName + "()'")
                      .addCompletions(ParseUtil.complete(session.getFunctions().keySet(), functionName));
                }

                // Parse function parameters
                ctx.skipWhitespace();
                final Object params = function.parseParams(session, ctx, complete);

                // Return node that applies the function to the parameters
                node = new Node() {
                    @Override
                    public Value evaluate(ParseSession session) {
                        return function.apply(session, params);
                    }
                };
                break;
            }
            case ".":
            {
                // Parse next atom - it must be an identifier, either a method or property name
                ctx.skipWhitespace();
                final Node memberNode = AtomParser.INSTANCE.parse(session, ctx, complete);
                if (!(memberNode instanceof IdentNode)) {
                    ctx.setIndex(mark);
                    throw new ParseException(ctx);
                }
                String member = ((IdentNode)memberNode).getName();

                // If first atom was an identifier, this must be a class name followed by a field or method name
                Class<?> cl = null;
                if (node instanceof IdentNode) {

                    // Keep parsing identifiers as long as we can; after each identifier, try to resolve a class name
                    final ArrayList<Integer> indexList = new ArrayList<>();
                    final ArrayList<Class<?>> classList = new ArrayList<>();
                    final ArrayList<String> memberList = new ArrayList<>();
                    String idents = ((IdentNode)node).getName();
                    while (true) {
                        classList.add(session.resolveClass(idents));
                        indexList.add(ctx.getIndex());
                        memberList.add(member);
                        final Matcher matcher = ctx.tryPattern("\\s*\\.\\s*(" + IdentNode.NAME_PATTERN + ")");
                        if (matcher == null)
                            break;
                        idents += "." + member;
                        member = matcher.group(1);
                    }

                    // Use the longest class name resolved, if any
                    for (int i = classList.size() - 1; i >= 0; i--) {
                        if ((cl = classList.get(i)) != null) {
                            ctx.setIndex(indexList.get(i));
                            member = memberList.get(i);
                            break;
                        }
                    }
                    if (cl == null)
                        throw new ParseException(ctx, "unknown class `" + idents + "'");        // TODO: tab-completions
                }

                // Handle property access
                if (ctx.tryPattern("\\s*\\(") == null) {                                        // not a method call
                    final String propertyName = member;
                    final Node target = node;

                    // Handle static fields
                    if (cl != null) {

                        // Handle "class" literal
                        if (propertyName.equals("class")) {
                            node = new LiteralNode(cl);
                            break;
                        }

                        // Return node accessing static field
                        node = new ConstNode(new StaticFieldValue(this.findStaticField(ctx, cl, propertyName, complete)));
                        break;
                    }

                    // Must be object property access
                    node = new Node() {
                        @Override
                        public Value evaluate(ParseSession session) {
                            return BaseExprParser.this.evaluateProperty(session, target.evaluate(session), propertyName);
                        }
                    };
                    break;
                }

                // Handle method call
                node = cl != null ?
                  new MethodInvokeNode(cl, member, BaseExprParser.parseParams(session, ctx, complete)) :
                  new MethodInvokeNode(node, member, BaseExprParser.parseParams(session, ctx, complete));
                break;
            }
            case "[":
            {
                this.spaceParser.parse(ctx, complete);
                final Node index = ExprParser.INSTANCE.parse(session, ctx, complete);
                this.spaceParser.parse(ctx, complete);
                if (!ctx.tryLiteral("]"))
                    throw new ParseException(ctx).addCompletion("] ");
                final Node target = node;
                node = new Node() {
                    @Override
                    public Value evaluate(ParseSession session) {
                        return Op.ARRAY_ACCESS.apply(session, target.evaluate(session), index.evaluate(session));
                    }
                };
                break;
            }
            case "++":
                node = this.createPostcrementNode("increment", node, true);
                break;
            case "--":
                node = this.createPostcrementNode("decrement", node, false);
                break;
            default:
                throw new RuntimeException("internal error: " + opsym);
            }
        }
    }

    // Find static field
    private Field findStaticField(ParseContext ctx, Class<?> cl, String fieldName, boolean complete) {
        final Field[] holder = new Field[1];
        try {
            this.findStaticField(ctx, cl, fieldName, holder);
            final Field field = holder[0];
            if (field == null)
                throw new ParseException(ctx, "class `" + cl.getName() + "' has no field named `" + fieldName + "'");
            if ((field.getModifiers() & Modifier.STATIC) == 0)
                throw new ParseException(ctx, "field `" + fieldName + "' in class `" + cl.getName() + "' is not a static field");
            return field;
        } catch (ParseException e) {
            throw complete ? e.addCompletions(this.getStaticNameCompletions(cl, fieldName)) : e;
        }
    }

    // Helper method for findStaticField()
    private void findStaticField(ParseContext ctx, Class<?> cl, String fieldName, Field[] holder) {

        // Find field with the given name declared in class, if any
        for (Field field : cl.getDeclaredFields()) {
            if (field.getName().equals(fieldName)) {
                if (holder[0] == null || (holder[0].getModifiers() & Modifier.STATIC) == 0)
                    holder[0] = field;
                else {
                    throw new ParseException(ctx, "field `" + fieldName + "' in class `" + cl.getName()
                      + "' is ambiguous, found in both " + holder[0].getDeclaringClass() + " and " + cl);
                }
                break;
            }
        }

        // Recurse on supertypes
        if (cl.getSuperclass() != null)
            this.findStaticField(ctx, cl.getSuperclass(), fieldName, holder);
        for (Class<?> iface : cl.getInterfaces())
            this.findStaticField(ctx, iface, fieldName, holder);
    }

    // Get all completions for some.class.Name.foo...
    private Iterable<String> getStaticNameCompletions(Class<?> cl, String name) {
        final TreeSet<String> names = new TreeSet<>();
        this.getStaticPropertyNames(cl, names);
        names.add("class");
        return ParseUtil.complete(this.getStaticPropertyNames(cl, names), name);
    }

    // Helper method for getStaticNameCompletions()
    private Iterable<String> getStaticPropertyNames(Class<?> cl, TreeSet<String> names) {

        // Add static field and method names
        for (Field field : cl.getDeclaredFields()) {
            if ((field.getModifiers() & Modifier.STATIC) != 0)
                names.add(field.getName());
        }
        for (Method method : cl.getDeclaredMethods()) {
            if ((method.getModifiers() & Modifier.STATIC) != 0)
                names.add(method.getName());
        }

        // Recurse on supertypes
        if (cl.getSuperclass() != null)
            this.getStaticPropertyNames(cl.getSuperclass(), names);
        for (Class<?> iface : cl.getInterfaces())
            this.getStaticPropertyNames(iface, names);

        // Done
        return names;
    }

    // Parse array literal
    private List<?> parseArrayLiteral(ParseSession session, ParseContext ctx, boolean complete, Class<?> elemType) {
        final ArrayList<Object> list = new ArrayList<>();
        this.spaceParser.parse(ctx, complete);
        if (!ctx.tryLiteral("{"))
            throw new ParseException(ctx).addCompletion("{ ");
        this.spaceParser.parse(ctx, complete);
        if (ctx.tryLiteral("}"))
            return list;
        while (true) {
            list.add(elemType.isArray() ?
              this.parseArrayLiteral(session, ctx, complete, elemType.getComponentType()) :
              ExprParser.INSTANCE.parse(session, ctx, complete));
            ctx.skipWhitespace();
            if (ctx.tryLiteral("}"))
                break;
            if (!ctx.tryLiteral(","))
                throw new ParseException(ctx, "expected `,'").addCompletion(", ");
            this.spaceParser.parse(ctx, complete);
        }
        return list;
    }

    // Get the array class with the given base type and dimensions
    private Class<?> getArrayClass(Class<?> base, int dimensions) {
        if (dimensions == 0)
            return base;
        final String suffix = base.isArray() ? base.getName() :
          base.isPrimitive() ? "" + Primitive.get(base).getLetter() : "L" + base.getName() + ";";
        final StringBuilder buf = new StringBuilder(dimensions + suffix.length());
        while (dimensions-- > 0)
            buf.append('[');
        buf.append(suffix);
        final String className = buf.toString();
        try {
            return Class.forName(className, false, Thread.currentThread().getContextClassLoader());
        } catch (Exception e) {
            throw new EvalException("can't load array class `" + className + "'");
        }
    }

    // Parse method parameters; we assume opening `(' has just been parsed
    static List<Node> parseParams(ParseSession session, ParseContext ctx, boolean complete) {
        final ArrayList<Node> params = new ArrayList<Node>();
        final SpaceParser spaceParser = new SpaceParser();
        spaceParser.parse(ctx, complete);
        if (ctx.tryLiteral(")"))
            return params;
        while (true) {
            params.add(ExprParser.INSTANCE.parse(session, ctx, complete));
            spaceParser.parse(ctx, complete);
            if (ctx.tryLiteral(")"))
                break;
            if (!ctx.tryLiteral(","))
                throw new ParseException(ctx, "expected `,'").addCompletion(", ");
            spaceParser.parse(ctx, complete);
        }
        return params;
    }

    private Value evaluateProperty(ParseSession session, Value value, final String name) {

        // Evaluate target
        final Object target = value.checkNotNull(session, "property `" + name + "' access");
        final Class<?> cl = target.getClass();

        // Handle properties of database objects (i.e., database fields)
        if (session.getMode().hasJSimpleDB() && target instanceof JObject) {

            // Get object and ID
            final JObject jobj = (JObject)target;
            final ObjId id = jobj.getObjId();

            // Resolve JField
            JField jfield0;
            try {
                jfield0 = ParseUtil.resolveJField(session, id, name);
            } catch (IllegalArgumentException e) {
                jfield0 = null;
            }
            final JField jfield = jfield0;

            // Return value reflecting the field if the field was found
            if (jfield instanceof JSimpleField)
                return new JSimpleFieldValue(jobj, (JSimpleField)jfield);
            else if (jfield != null)
                return new JFieldValue(jobj, jfield);
        } else if (session.getMode().hasCoreAPI() && target instanceof ObjId) {
            final ObjId id = (ObjId)target;

            // Resolve field
            org.jsimpledb.core.Field<?> field0;
            try {
                field0 = ParseUtil.resolveField(session, id, name);
            } catch (IllegalArgumentException e) {
                field0 = null;
            }
            final org.jsimpledb.core.Field<?> field = field0;

            // Return value reflecting the field if the field was found
            if (field instanceof SimpleField)
                return new SimpleFieldValue(id, (SimpleField<?>)field);
            else if (field != null)
                return new FieldValue(id, field);
        }

        // Try bean property accessed via bean methods
        final BeanInfo beanInfo;
        try {
            beanInfo = Introspector.getBeanInfo(cl);
        } catch (IntrospectionException e) {
            throw new EvalException("error introspecting class `" + cl.getName() + "': " + e, e);
        }
        for (PropertyDescriptor propertyDescriptor : beanInfo.getPropertyDescriptors()) {
            if (propertyDescriptor instanceof IndexedPropertyDescriptor)
                continue;
            if (!propertyDescriptor.getName().equals(name))
                continue;
            final Method getter = this.makeAccessible(propertyDescriptor.getReadMethod());
            final Method setter = this.makeAccessible(propertyDescriptor.getWriteMethod());
            if (getter != null && setter != null)
                return new MutableBeanPropertyValue(target, propertyDescriptor.getName(), getter, setter);
            else if (getter != null)
                return new BeanPropertyValue(target, propertyDescriptor.getName(), getter);
        }

        // Try instance field
        /*final*/ Field javaField;
        try {
            javaField = cl.getField(name);
        } catch (NoSuchFieldException e) {
            javaField = null;
        }
        if (javaField != null)
            return new ObjectFieldValue(target, javaField);

        // Try array.length
        if (target.getClass().isArray() && name.equals("length"))
            return new ConstValue(Array.getLength(target));

        // Not found
        throw new EvalException("property `" + name + "' not found in " + cl);
    }

    // Workaround the problem where non-public class C implements public method M of interface I.
    // In that case, invoking method C.M results in IllegalAccessException; instead, you have to invoke I.M.
    private Method makeAccessible(Method method) {
        if (method == null)
            return null;
        Class<?> cl = method.getDeclaringClass();
        if ((cl.getModifiers() & Modifier.PUBLIC) != 0)
            return method;
        do {
            for (Class<?> iface : cl.getInterfaces()) {
                try {
                    return iface.getMethod(method.getName(), method.getParameterTypes());
                } catch (NoSuchMethodException e) {
                    continue;
                }
            }
        } while ((cl = cl.getSuperclass()) != null);
        return null;
    }

    private Node createPostcrementNode(final String operation, final Node node, final boolean increment) {
        return new Node() {
            @Override
            public Value evaluate(ParseSession session) {
                return node.evaluate(session).xxcrement(session, "post-" + operation, increment);
            }
        };
    }
}

