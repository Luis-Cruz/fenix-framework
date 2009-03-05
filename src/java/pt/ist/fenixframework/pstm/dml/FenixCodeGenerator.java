package pt.ist.fenixframework.pstm.dml;

import java.util.*;
import java.io.*;
import dml.*;

import pt.ist.fenixframework.pstm.AbstractDomainObject;
import pt.ist.fenixframework.pstm.OJBFunctionalSetWrapper;
import pt.ist.fenixframework.pstm.RelationList;
import pt.ist.fenixframework.pstm.ResultSetReader;
import pt.ist.fenixframework.pstm.ToSqlConverter;
import pt.ist.fenixframework.pstm.Transaction;
import pt.ist.fenixframework.pstm.VBox;

public class FenixCodeGenerator extends CodeGenerator {
    private static final String RESULT_SET_READER_CLASS = ResultSetReader.class.getName();
    private static final String TO_SQL_CONVERTER_CLASS = ToSqlConverter.class.getName();


    public FenixCodeGenerator(CompilerArgs compArgs, DomainModel domainModel) {
        super(compArgs, domainModel);
    }

    @Override
    protected String getDomainClassRoot() {
        return AbstractDomainObject.class.getName();
    }

    protected void generateFilePreamble(String subPackageName, PrintWriter out) {
        generatePackageDecl(subPackageName, out);
        println(out, "import " + VBox.class.getName() + ";");
        println(out, "import " + RelationList.class.getName() + ";");
        println(out, "import " + OJBFunctionalSetWrapper.class.getName() + ";");
    }


    protected void generateBaseClassBody(DomainClass domClass, PrintWriter out) {
        super.generateBaseClassBody(domClass, out);
        generateCheckDisconnected(domClass, out);
        generateDatabaseReader(domClass.getSlots(), domClass.hasSuperclass(), out);
    }

    protected void generateBaseClassConstructorsBody(DomainClass domClass, PrintWriter out) {
	super.generateBaseClassConstructorsBody(domClass, out);
        final Slot ojbConcreteClassSlot = domClass.findSlot("ojbConcreteClass");
        if (ojbConcreteClassSlot != null && calculateHierarchyLevel(domClass) == 1) {
            newline(out);
            print(out, "setOjbConcreteClass(getClass().getName());");
        }
    }
    
    private int calculateHierarchyLevel(DomainEntity domainEntity) {
	return hasSuperclass(domainEntity) ? calculateHierarchyLevel(((DomainClass) domainEntity).getSuperclass()) + 1 : 0;
    }

    private boolean hasSuperclass(DomainEntity domainEntity) {
	return domainEntity != null && domainEntity instanceof DomainClass && ((DomainClass) domainEntity).getSuperclass() != null;
    }

    protected void generateStaticRelationSlots(Role role, PrintWriter out) {
        super.generateStaticRelationSlots(role, out);

        if (role.isFirstRole()
            && (role.getMultiplicityUpper() != 1) 
            && (role.getOtherRole().getMultiplicityUpper() != 1)) {

            // a relation many-to-many need a listener...
            Role otherRole = role.getOtherRole();
            String firstType = getTypeFullName(otherRole.getType());
            String secondType = getTypeFullName(role.getType());
            String relationName = getRelationSlotNameFor(role);

            newline(out);
            printWords(out, "static");
            newBlock(out);
            printWords(out, relationName);
            print(out, ".addListener(new ");
            print(out, makeGenericType("dml.runtime.RelationAdapter", firstType, secondType));
            print(out, "()");
            newBlock(out);

            println(out, "@Override");
            printMethod(out, "public", "void", "beforeAdd",
                        makeArg(firstType, "arg0"),
                        makeArg(secondType, "arg1"));
            startMethodBody(out);
            generateRelationRegisterCall("addRelationTuple", role, otherRole, out);
            endMethodBody(out);

            println(out, "@Override");
            printMethod(out, "public", "void", "beforeRemove",
                        makeArg(firstType, "arg0"),
                        makeArg(secondType, "arg1"));
            startMethodBody(out);
            generateRelationRegisterCall("removeRelationTuple", role, otherRole, out);
            endMethodBody(out);

            closeBlock(out);
            print(out, ");");
            closeBlock(out);
        }
    }

    protected void generateRelationRegisterCall(String regMethodName, Role r0, Role r1, PrintWriter out) {
        String r0name = r0.getName();
        String r1name = r1.getName();

        print(out, Transaction.class.getName());
        print(out, ".");
        print(out, regMethodName);
        print(out, "(\"");
        print(out, getEntityFullName(r0.getRelation()));
        print(out, "\", arg1, \"");
        print(out, (r1name == null) ? "" : r1name);
        print(out, "\", arg0, \"");
        print(out, (r0name == null) ? "" : r0name);
        print(out, "\");");
    }

    protected void generateSetterBody(String setterName, String slotName, String typeName, PrintWriter out) {
        if (! setterName.startsWith("set$")) {
            print(out, getSlotExpression(slotName));
            print(out, ".put(this, \"");
            print(out, slotName);
            print(out, "\", ");
            print(out, slotName);
            print(out, ");");
        } else {
            super.generateSetterBody(setterName, slotName, typeName, out);
        }
    }

    protected void generateInitInstance(Iterator slotsIter, Iterator roleSlotsIter, PrintWriter out) {
        // generate initInstance method to be used by OJB
        onNewline(out);
        newline(out);
        printMethod(out, "private", "void", "initInstance");
        startMethodBody(out);
        print(out, "initInstance(true);");
        endMethodBody(out);

        super.generateInitInstance(slotsIter, roleSlotsIter, out);
    }

    protected String getNewSlotExpression(Slot slot) {
        return "VBox.makeNew(allocateOnly, false)";
    }

    protected String getNewRoleOneSlotExpression(Role role) {
        return "VBox.makeNew(allocateOnly, true)";
    }

    protected String getNewRoleStarSlotExpression(Role role) {
        StringBuilder buf = new StringBuilder();

        // generate the relation aware collection
        String thisType = getTypeFullName(role.getOtherRole().getType());
        buf.append("new ");
        buf.append(getRelationAwareTypeFor(role));
        buf.append("((");
        buf.append(thisType);
        buf.append(")this, ");
        buf.append(getRelationSlotNameFor(role));
        buf.append(", \"");
        buf.append(role.getName());
        buf.append("\", allocateOnly)");

        return buf.toString();
    }

    protected String getRelationAwareBaseTypeFor(Role role) {
        // FIXME: handle other types of collections other than sets
        return "RelationList";
    }

    protected String getBoxBaseType() {
        return "VBox";
    }

    protected String getRoleArgs(Role role) {
        String args = super.getRoleArgs(role);
        if ((role.getName() != null) && (role.getMultiplicityUpper() == 1)) {
            if (args.length() > 0) {
                args += ", ";
            }
            args += "\"" + role.getName() + "\"";
        }
        return args;
    }

    protected String getRoleOneBaseType() {
        return "dml.runtime.RoleOneFenix";
    }


    protected void generateRoleClassGetter(Role role, Role otherRole, PrintWriter out) {
        super.generateRoleClassGetter(role, otherRole, out);

        // generate also the getFkBox method
        if (role.getMultiplicityUpper() == 1) {
            print(out, "public void setFk(");
            print(out, getTypeFullName(otherRole.getType()));
            print(out, " o1, java.lang.Integer newFk)");
            startMethodBody(out);
            print(out, "o1.setKey");
            print(out, capitalize(role.getName()));
            print(out, "(newFk);");
            endMethodBody(out);
        }
    }


    protected void generateGetterBody(String slotName, String typeName, PrintWriter out) {
        printWords(out, "return", getSlotExpression(slotName));
        print(out, ".get(this, \"");
        print(out, slotName);
        print(out, "\");");
    }

    protected void generateRelationGetter(String getterName, String valueToReturn, String typeName, PrintWriter out) {
        newline(out);
        printFinalMethod(out, "public", typeName, getterName);

        startMethodBody(out);
        print(out, "return ");
        print(out, valueToReturn);
        print(out, ";");
        endMethodBody(out);
    }

    protected void generateRoleSlotMethodsMultStar(Role role, PrintWriter out) {
        super.generateRoleSlotMethodsMultStar(role, out);

        String paramListType = makeGenericType("java.util.List", getTypeFullName(role.getType()));

        generateRelationGetter("get" + capitalize(role.getName()), role.getName(), paramListType, out);
        //generateRelationGetter("get$" + role.getName(), "null", "OJBFunctionalSetWrapper", out);
        generateOJBSetter(role.getName(), "OJBFunctionalSetWrapper", out);
        generateIteratorMethod(role, out);
    }

    protected void generateIteratorMethod(Role role, PrintWriter out) {
        newline(out);
        printFinalMethod(out, 
                         "public", 
                         makeGenericType("java.util.Iterator", getTypeFullName(role.getType())),
                         "get" + capitalize(role.getName()) + "Iterator");
        startMethodBody(out);
        printWords(out, "return", role.getName());
        print(out, ".iterator();");
        endMethodBody(out);
    }

    @Override
    protected void generateSlotAccessors(Slot slot, PrintWriter out) {
        super.generateSlotAccessors(slot, out);
        generateExternalizationGetter(slot, out);
        generateInternalizationSetter(slot, out);
    }

    protected void generateExternalizationGetter(Slot slot, PrintWriter out) {
        newline(out);
        printFinalMethod(out, "private", "Object", "get$" + slot.getName());

        startMethodBody(out);
        // handle nulls (if the slot value is null, then return null)
        print(out, slot.getTypeName());
        print(out, " value = get");
        print(out, capitalize(slot.getName()));
        println(out, "();");
        
        print(out, "return ");

        if (FenixDomainModel.isNullableType(slot.getSlotType())) {
            print(out, "(value == null) ? null : ");
        }

        print(out, getExternalizationExpression(slot));
        print(out, ";");
        endMethodBody(out);            
    }

    protected String getExternalizationExpression(Slot slot) {
        StringBuilder expression = new StringBuilder();

        // start with the variable holding the slot value (not null)
        expression.append("value");

        // now, go through the externalization elements, externalizing this value
        ValueType vt = slot.getSlotType();
        
        while (! (vt.isBuiltin() || vt.isEnum())) {
            List<ExternalizationElement> extElems = vt.getExternalizationElements();
            if (extElems.size() != 1) {
                throw new Error("Can't handle value-types with more than one externalization element yet...");
            }

            ExternalizationElement extElem = extElems.get(0);
            String extMethodName = extElem.getMethodName();

            if (extMethodName.contains(".")) {
                // a static method
                expression.insert(0, extMethodName + "(");
                expression.append(")");
            } else {
                // a class-member method
                expression.append(".");
                expression.append(extMethodName);
                expression.append("()");
            }

            vt = extElem.getType();
        }

        // wrap the expression with the final converter method call
        // note that this is being constructed backwards...
        if (vt.isEnum()) {
            expression.insert(0, "Enum(");
        } else {
            expression.insert(0, vt.getDomainName() + "(");
        }

        expression.insert(0, ".getValueFor");
        expression.insert(0, TO_SQL_CONVERTER_CLASS);

        //close the wrap-up
        expression.append(")");

        return expression.toString();
    }


    protected void generateInternalizationSetter(Slot slot, PrintWriter out) {
        newline(out);
        print(out, "private final void set$");
        print(out, slot.getName());
        print(out, "(");

        ValueType vt = slot.getSlotType();
        
        while (! (vt.isBuiltin() || vt.isEnum())) {
            List<ExternalizationElement> extElems = vt.getExternalizationElements();
            if (extElems.size() != 1) {
                throw new Error("Can't handle value-types with more than one externalization element yet...");
            }

            ExternalizationElement extElem = extElems.get(0);
            vt = extElem.getType();
        }
        print(out, vt.getFullname());
        print(out, " arg0, int txNumber)");


        startMethodBody(out);
        print(out, "this.");
        print(out, slot.getName());
        print(out, ".persistentLoad(");

        if (FenixDomainModel.isNullableType(vt)) {
            print(out, "(arg0 == null) ? null : ");
        }

        print(out, getRsReaderExpression(slot));
        print(out, ", txNumber);");
        endMethodBody(out);            
    }

    protected String getRsReaderExpression(Slot slot) {
        StringBuilder buf = new StringBuilder();
        buildReconstructionExpression(buf, slot.getSlotType(), 0);
        return buf.toString();
    }

    protected int buildReconstructionExpression(StringBuilder buf, ValueType vt, int colNum) {

        // first, check if is a built-in value type
        // if it is, then process it and return
        if (vt.isBuiltin()) {
            buf.append("arg" + colNum);
            return colNum + 1;
        }

        // it is not built-in, process it normally
        String intMethodName = vt.getInternalizationMethodName();

        // if no internalizationMethodName is present, then use the constructor
        if (intMethodName == null) {
            buf.append("new ");
            buf.append(vt.getFullname());
        } else {
            if (! intMethodName.contains(".")) {
                // assume that non-dotted names correspond to static methods of the ValueType vt
                buf.append(vt.getFullname());
                buf.append(".");
            }

            buf.append(intMethodName);
        }

        buf.append("(");

        for (ExternalizationElement extElem : vt.getExternalizationElements()) {
            if (colNum > 0) {
                buf.append(", ");
            }
            colNum = buildReconstructionExpression(buf, extElem.getType(), colNum);
        }

        buf.append(")");

        return colNum;
    }




    protected void generateOJBSetter(String slotName, String typeName, PrintWriter out) {
        newline(out);
        printFinalMethod(out, "public", "void", "set$" + slotName, makeArg(typeName, slotName));

        startMethodBody(out);
        printWords(out, getSlotExpression(slotName));
        print(out, ".setFromOJB(this, \"");
        print(out, slotName);
        print(out, "\", ");
        print(out, slotName);
        print(out, ");");
        endMethodBody(out);            
    }


    protected void generateRoleSlotMethodsMultOne(Role role, PrintWriter out) {
        super.generateRoleSlotMethodsMultOne(role, out);

        String typeName = getTypeFullName(role.getType());
        String slotName = role.getName();
        //generateGetter("public", "get$" + slotName, slotName, typeName, out);
        generateOJBSetter(slotName, typeName, out);
    }

    protected void generateCheckDisconnected(DomainClass domClass, PrintWriter out) {
        newline(out);
        printMethod(out, "protected", "boolean", "checkDisconnected");
        startMethodBody(out);
        
        if (domClass.hasSuperclass()) {
            println(out, "if (! super.checkDisconnected()) return false;");
        }

        Iterator<Role> roleSlotsIter = domClass.getRoleSlots();
        while (roleSlotsIter.hasNext()) {
            Role role = roleSlotsIter.next();

            if (role.getName() != null) {
                onNewline(out);

                print(out, "if (");
                if (role.getMultiplicityUpper() == 1) {
                    print(out, "has");
                } else {
                    print(out, "hasAny");
                }
                print(out, capitalize(role.getName()));
                println(out, "()) return false;");
            }
        }

        println(out, "return true;");
        endMethodBody(out);
    }


    // -----------------------------------------------------------------------------------
    // code related to the database reading/writing


    protected void generateDatabaseReader(Iterator slotsIter, boolean hasSuperclass, PrintWriter out) {
        newline(out);
        printMethod(out, "protected", "void", "readSlotsFromResultSet", 
                    makeArg("java.sql.ResultSet", "rs"),
                    makeArg("int", "txNumber"));
        print(out, " throws java.sql.SQLException");
        startMethodBody(out);

        if (hasSuperclass) {
            println(out, "super.readSlotsFromResultSet(rs, txNumber);");
        }

        while (slotsIter.hasNext()) {
            Slot slot = (Slot)slotsIter.next();
            
            onNewline(out);
            print(out, "set$");
            print(out, slot.getName());
            print(out, "(");
            printRsReaderExpressions(out, slot.getSlotType(), convertToDBStyle(slot.getName()), 0);
            print(out, ", txNumber);");
        }
        
        endMethodBody(out);
    }

    protected int printRsReaderExpressions(PrintWriter out, ValueType vt, String colBaseName, int colNum) {
        if (vt.isBuiltin()) {
            printBuiltinReadExpression(out, vt, colBaseName, colNum);
            return colNum + 1;
        }

        for (ExternalizationElement extElem : vt.getExternalizationElements()) {
            colNum = printRsReaderExpressions(out, extElem.getType(), colBaseName, colNum);
        }

        return colNum;
    }


    protected void printBuiltinReadExpression(PrintWriter out, ValueType vt, String colBaseName, int colNum) {
        print(out, RESULT_SET_READER_CLASS);
        print(out, ".read");

        if (vt.isEnum()) {
            print(out, "Enum(");
            print(out, vt.getFullname());
            print(out, ".class, ");
        } else {
            print(out, vt.getDomainName());
            print(out, "(");
        }

        print(out, "rs, \"");
        print(out, colBaseName);
        if (colNum > 0) {
            print(out, "__" + colNum);
        }
        print(out, "\")");
    }


    // MAJOR HACK!!!!!  The two following methods were copied verbatim from the net.sourceforge.fenixedu.util.StringFormatter class
    private static String splitCamelCaseString(String string) {

        StringBuilder result = new StringBuilder();
        boolean first = true;
        for (char c : string.toCharArray()) {
            if (first) {
                first = false;
            } else if (Character.isUpperCase(c)) {
                result.append(' ');
            }
            result.append(c);
        }

        return result.toString();
    }

    private static String convertToDBStyle(String string) {
        return splitCamelCaseString(string).replace(' ', '_').toUpperCase();
    }
}

