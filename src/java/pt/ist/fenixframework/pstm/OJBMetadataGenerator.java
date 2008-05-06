package pt.ist.fenixframework.pstm;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Iterator;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.TreeSet;

import pt.ist.fenixframework.pstm.ojb.FenixPersistentField;
import pt.ist.fenixframework.pstm.ojb.DomainAllocator;

import org.apache.commons.lang.StringUtils;
import org.apache.ojb.broker.metadata.ClassDescriptor;
import org.apache.ojb.broker.metadata.CollectionDescriptor;
import org.apache.ojb.broker.metadata.DescriptorRepository;
import org.apache.ojb.broker.metadata.FieldDescriptor;
import org.apache.ojb.broker.metadata.MetadataManager;
import org.apache.ojb.broker.metadata.ObjectReferenceDescriptor;
import org.apache.ojb.broker.metadata.fieldaccess.PersistentField;
import org.apache.ojb.broker.accesslayer.conversions.FieldConversion;

import dml.DmlCompiler;
import dml.DomainClass;
import dml.DomainEntity;
import dml.DomainModel;
import dml.Role;
import dml.Slot;

/**
 * @author - Shezad Anavarali (shezad@ist.utl.pt)
 * 
 */
public class OJBMetadataGenerator {

    private static final String DOMAIN_OBJECT_CLASSNAME = "net.sourceforge.fenixedu.domain.DomainObject";

    private static final Set<String> unmappedObjectReferenceAttributesInDML = new TreeSet<String>();

    private static final Set<String> unmappedCollectionReferenceAttributesInDML = new TreeSet<String>();

    private static final Set<String> unmappedObjectReferenceAttributesInOJB = new TreeSet<String>();

    private static final Set<String> unmappedCollectionReferenceAttributesInOJB = new TreeSet<String>();

    private static String classToDebug = null;

    private static ResourceBundle rbConversors = ResourceBundle.getBundle("dataTypeConversors");

    private static ResourceBundle rbJDBCTypes = ResourceBundle.getBundle("dataTypesJDBCTypes");

    /**
     * @param args
     * @throws Exception
     */
    public static void main(String[] args) throws Exception {

        String[] dmlFilesArray = { args[0] };
        if (args.length == 2) {
            classToDebug = args[1];
        }

        DomainModel domainModel = DmlCompiler.getFenixDomainModel(dmlFilesArray);
        Map ojbMetadata = MetadataManager.getInstance().getGlobalRepository().getDescriptorTable();

        updateOJBMappingFromDomainModel(ojbMetadata, domainModel);

        printUnmmapedAttributes(unmappedObjectReferenceAttributesInDML,
                "UnmappedObjectReferenceAttributes in DML:");
        printUnmmapedAttributes(unmappedCollectionReferenceAttributesInDML,
                "UnmappedCollectionReferenceAttributes in DML:");
        printUnmmapedAttributes(unmappedObjectReferenceAttributesInOJB,
                "UnmappedObjectReferenceAttributes in OJB:");
        printUnmmapedAttributes(unmappedCollectionReferenceAttributesInOJB,
                "UnmappedCollectionReferenceAttributes in OJB:");

        System.exit(0);

    }

    private static void printUnmmapedAttributes(Set<String> unmappedAttributesSet, String title) {
        if (!unmappedAttributesSet.isEmpty()) {
            System.out.println();
            System.out.println(title);
            for (String objectReference : unmappedAttributesSet) {
                System.out.println(objectReference);
            }
        }
    }

    public static void updateOJBMappingFromDomainModel(Map ojbMetadata, DomainModel domainModel)
            throws Exception {

	final DescriptorRepository descriptorRepository = MetadataManager.getInstance().getGlobalRepository();

        for (final Iterator iterator = domainModel.getClasses(); iterator.hasNext();) {
            final DomainClass domClass = (DomainClass) iterator.next();
            final String classname = domClass.getFullName();
            if (!classname.equals(DOMAIN_OBJECT_CLASSNAME)) {
        	final Class clazz = Class.forName(classname);
        	final ClassDescriptor classDescriptor = new ClassDescriptor(descriptorRepository);
        	classDescriptor.setClassOfObject(clazz);
        	classDescriptor.setTableName(getExpectedTableName(domClass));
        	ojbMetadata.put(domClass.getFullName(), classDescriptor);
        	MetadataManager.getInstance().getRepository().getDescriptorTable().put(classname, classDescriptor);
            }
        }

       for (final Iterator iterator = domainModel.getClasses(); iterator.hasNext();) {
            final DomainClass domClass = (DomainClass) iterator.next();
            final String classname = domClass.getFullName();
            if (!classname.equals(DOMAIN_OBJECT_CLASSNAME)) {

        	final Class clazz = Class.forName(classname);
        	final ClassDescriptor classDescriptor = (ClassDescriptor) ojbMetadata.get(classname);

        	addClassExtentOfAncesterClassDescriptors(ojbMetadata, domClass.getSuperclass(), clazz);

        	if (classDescriptor != null) {
        	    setFactoryMethodAndClass(classDescriptor);

        	    updateFields(domainModel, classDescriptor, domClass, ojbMetadata, clazz);
        	    if (!Modifier.isAbstract(clazz.getModifiers())) {
        		updateRelations(classDescriptor, domClass, ojbMetadata, clazz);
        	    }
                
        	    if (classToDebug != null && classDescriptor.getClassNameOfObject().contains(classToDebug)) {
        		System.out.println(classDescriptor.toXML());
        	    }
        	}
            }

        }

    }

    private static void addClassExtentOfAncesterClassDescriptors(final Map ojbMetadata, final DomainEntity domainEntity, final Class clazz) {
	if (domainEntity != null && domainEntity instanceof DomainClass) {
	    final DomainClass domainClass = (DomainClass) domainEntity;
	    final String ancesterClassname = domainClass.getFullName();
	    if (!ancesterClassname.equals(DOMAIN_OBJECT_CLASSNAME)) {
		final ClassDescriptor classDescriptor = (ClassDescriptor) ojbMetadata.get(ancesterClassname);
		classDescriptor.addExtentClass(clazz);
		addClassExtentOfAncesterClassDescriptors(ojbMetadata, domainClass.getSuperclass(), clazz);
	    }
	}
    }

    private static String getExpectedTableName(final DomainClass domainClass) {
	if (domainClass.getFullName().equals(DOMAIN_OBJECT_CLASSNAME)) {
	    return null;
	}
	if (domainClass.getSuperclass() == null ||
		(domainClass.getSuperclass() instanceof DomainClass && domainClass.getSuperclass().getFullName().equals(DOMAIN_OBJECT_CLASSNAME))) {
	    return getTableName(domainClass.getName());
	}
	return domainClass.getSuperclass() instanceof DomainClass ? getExpectedTableName((DomainClass) domainClass.getSuperclass()) : null;
    }

    private static String getTableName(final String name) {
	final StringBuilder stringBuilder = new StringBuilder();
	boolean isFirst = true;
	for (final char c : name.toCharArray()) {
	    if (isFirst) {
		isFirst = false;
		stringBuilder.append(Character.toUpperCase(c));
	    } else {
		if (Character.isUpperCase(c)) {
		    stringBuilder.append('_');
		    stringBuilder.append(c);
		} else {
		    stringBuilder.append(Character.toUpperCase(c));
		}
	    }
	}
	return stringBuilder.toString();
    }

    private static void setFactoryMethodAndClass(ClassDescriptor cld) {
        // this will eventually disappear
        // I'll keep it here for debug only, for now
        cld.setFactoryClass(DomainAllocator.class);
        cld.setFactoryMethod("allocate");
    }


    protected static void updateFields(final DomainModel domainModel,
                                       final ClassDescriptor classDescriptor,
                                       final DomainClass domClass, 
                                       final Map ojbMetadata, 
                                       final Class persistentFieldClass) throws Exception {

        DomainEntity domEntity = domClass;
        int fieldID = 1;

        addFieldDescriptor(domainModel, "idInternal", "java.lang.Integer", fieldID++, classDescriptor, persistentFieldClass);

        while (domEntity instanceof DomainClass) {
            DomainClass dClass = (DomainClass) domEntity;

            Iterator<Slot> slots = dClass.getSlots();
            while (slots.hasNext()) {
                Slot slot = slots.next();

                addFieldDescriptor(domainModel, slot.getName(), slot.getType(), fieldID++, classDescriptor, persistentFieldClass);
            }

            domEntity = dClass.getSuperclass();
        }

    }

    protected static void addFieldDescriptor(DomainModel domainModel,
                                             String slotName,
                                             String slotType,
                                             int fieldID, 
                                             ClassDescriptor classDescriptor,
                                             Class persistentFieldClass) throws Exception {
        if (classDescriptor.getFieldDescriptorByName(slotName) == null){
            FieldDescriptor fieldDescriptor = new FieldDescriptor(classDescriptor, fieldID);
            fieldDescriptor.setColumnName(convertToDBStyle(slotName));
            fieldDescriptor.setAccess("readwrite");

            if (slotName.equals("idInternal")) {
                fieldDescriptor.setPrimaryKey(true);
                fieldDescriptor.setAutoIncrement(true);
            }
            PersistentField persistentField = new FenixPersistentField(persistentFieldClass, slotName);
            fieldDescriptor.setPersistentField(persistentField);

            String converter = null;
            try {
                converter = rbConversors.getString(slotType);
            } catch (MissingResourceException e) {
                // it's ok if some type does not exist in this bundle
                // so, ignore the errors that may occur
            }

            // specifying a converter overrides the special handling of enums
            boolean isEnum = ((converter == null) && domainModel.isEnumType(slotType));

            if (isEnum) {
                // we can't use a Class.forName(slotType) here because the value
                // of slotType is not correct for inner classes: it uses the Java source code 
                // notation (a dot) rather than the $ needed by the Class.forName method
                Class<? extends Enum> enumClass = (Class<? extends Enum>) persistentField.getType();
                fieldDescriptor.setFieldConversion(new Enum2SqlConversion(enumClass));
            } else {
                if (converter != null) {
                    fieldDescriptor.setFieldConversionClassName(converter.trim());
                }
            }

            String sqlType = (isEnum ? "VARCHAR" : rbJDBCTypes.getString(slotType).trim());
            fieldDescriptor.setColumnType(sqlType);

            classDescriptor.addFieldDescriptor(fieldDescriptor);
        }
    }

    protected static void updateRelations(final ClassDescriptor classDescriptor,
            final DomainClass domClass, Map ojbMetadata, Class persistentFieldClass) throws Exception {

        DomainEntity domEntity = domClass;
        while (domEntity instanceof DomainClass) {
            DomainClass dClass = (DomainClass) domEntity;

            // roles
            Iterator roleSlots = dClass.getRoleSlots();
            while (roleSlots.hasNext()) {
                Role role = (Role) roleSlots.next();
                String roleName = role.getName();

                if (domClass.getFullName().equals("net.sourceforge.fenixedu.domain.RootDomainObject")
                        && roleName != null
                        && (roleName.equals("rootDomainObject") || roleName.equals("rootDomainObjects"))) {
                    continue;
                }

                if (role.getMultiplicityUpper() == 1) {

                    // reference descriptors
                    if (classDescriptor.getObjectReferenceDescriptorByName(roleName) == null) {

                        String foreignKeyField = "key" + StringUtils.capitalize(roleName);

                        if (findSlotByName(dClass, foreignKeyField) == null) {
                            unmappedObjectReferenceAttributesInDML.add(foreignKeyField + " -> "
                                    + dClass.getName());
                            continue;
                        }

                        if (classDescriptor.getFieldDescriptorByName(foreignKeyField) == null) {
                            Class classToVerify = Class.forName(dClass.getFullName());
                            if (!Modifier.isAbstract(classToVerify.getModifiers())) {
                                unmappedObjectReferenceAttributesInOJB.add(foreignKeyField + " -> "
                                        + dClass.getName());
                                continue;
                            }
                        }

                        generateReferenceDescriptor(classDescriptor, persistentFieldClass, role,
                                roleName, foreignKeyField);

                    }
                } else {

                    // collection descriptors
                    if (classDescriptor.getCollectionDescriptorByName(roleName) == null) {

                        CollectionDescriptor collectionDescriptor = new CollectionDescriptor(
                                classDescriptor);

                        if (role.getOtherRole().getMultiplicityUpper() == 1) {

                            String foreignKeyField = "key"
                                    + StringUtils.capitalize(role.getOtherRole().getName());

                            if (findSlotByName((DomainClass) role.getType(), foreignKeyField) == null) {
                                unmappedCollectionReferenceAttributesInDML.add(foreignKeyField + " | "
                                        + ((DomainClass) role.getType()).getName() + " -> "
                                        + dClass.getName());
                                continue;
                            }

                            ClassDescriptor otherClassDescriptor = (ClassDescriptor) ojbMetadata
                                    .get(((DomainClass) role.getType()).getFullName());

                            if (otherClassDescriptor == null) {
                                System.out.println("Ignoring "
                                        + ((DomainClass) role.getType()).getFullName());
                                continue;
                            }

                            generateOneToManyCollectionDescriptor(collectionDescriptor, foreignKeyField);

                        } else {
                            generateManyToManyCollectionDescriptor(collectionDescriptor, role);

                        }
                        updateCollectionDescriptorWithCommonSettings(classDescriptor,
                                persistentFieldClass, role, roleName, collectionDescriptor);
                    }
                }
            }

            domEntity = dClass.getSuperclass();
        }
    }

    private static void updateCollectionDescriptorWithCommonSettings(
            final ClassDescriptor classDescriptor, Class persistentFieldClass, Role role,
            String roleName, CollectionDescriptor collectionDescriptor) throws ClassNotFoundException {
        collectionDescriptor.setItemClass(Class.forName(role.getType().getFullName()));
        collectionDescriptor.setPersistentField(persistentFieldClass, roleName);
        collectionDescriptor.setRefresh(false);
        collectionDescriptor.setCascadingStore(ObjectReferenceDescriptor.CASCADE_NONE);
        collectionDescriptor.setCollectionClass(OJBFunctionalSetWrapper.class);
        collectionDescriptor.setCascadeRetrieve(false);
        collectionDescriptor.setLazy(false);
        classDescriptor.addCollectionDescriptor(collectionDescriptor);
    }

    private static void generateManyToManyCollectionDescriptor(
            CollectionDescriptor collectionDescriptor, Role role) {

        String indirectionTableName = convertToDBStyle(role.getRelation().getName());
        String fkToItemClass = "KEY_" + convertToDBStyle(role.getType().getName());
        String fkToThisClass = "KEY_" + convertToDBStyle(role.getOtherRole().getType().getName());

        if (fkToItemClass.equals(fkToThisClass)) {
            fkToItemClass = fkToItemClass + "_" + convertToDBStyle(role.getName());
            fkToThisClass = fkToThisClass + "_" + convertToDBStyle(role.getOtherRole().getName());
        }

        collectionDescriptor.setIndirectionTable(indirectionTableName);
        collectionDescriptor.addFkToItemClass(fkToItemClass);
        collectionDescriptor.addFkToThisClass(fkToThisClass);
        collectionDescriptor.setCascadingStore(ObjectReferenceDescriptor.CASCADE_NONE);
        collectionDescriptor.setCascadingDelete(ObjectReferenceDescriptor.CASCADE_NONE);
    }

    private static void generateOneToManyCollectionDescriptor(CollectionDescriptor collectionDescriptor,
            String foreignKeyField) {
        collectionDescriptor.setCascadingStore(ObjectReferenceDescriptor.CASCADE_NONE);
        collectionDescriptor.addForeignKeyField(foreignKeyField);
    }

    private static void generateReferenceDescriptor(final ClassDescriptor classDescriptor,
            Class persistentFieldClass, Role role, String roleName, String foreignKeyField)
            throws ClassNotFoundException {
        ObjectReferenceDescriptor referenceDescriptor = new ObjectReferenceDescriptor(classDescriptor);
        referenceDescriptor.setItemClass(Class.forName(role.getType().getFullName()));
        referenceDescriptor.addForeignKeyField(foreignKeyField);
        referenceDescriptor.setPersistentField(persistentFieldClass, roleName);
        referenceDescriptor.setCascadeRetrieve(false);
        referenceDescriptor.setCascadingStore(ObjectReferenceDescriptor.CASCADE_NONE);
        referenceDescriptor.setLazy(false);

        classDescriptor.addObjectReferenceDescriptor(referenceDescriptor);
    }

    private static Slot findSlotByName(DomainClass domainClass, String slotName) {
        DomainClass domainClassIter = domainClass;
        while (domainClassIter != null) {

            for (Iterator<Slot> slotsIter = domainClassIter.getSlots(); slotsIter.hasNext();) {
                Slot slot = (Slot) slotsIter.next();
                if (slot.getName().equals(slotName)) {
                    return slot;
                }
            }

            domainClassIter = (DomainClass) domainClassIter.getSuperclass();
        }

        return null;
    }

    private static String convertToDBStyle(String string) {
	StringBuilder result = new StringBuilder(string.length() + 10);
	boolean first = true;
	for (char c : string.toCharArray()) {
	    if (first) {
		first = false;
	    } else if (Character.isUpperCase(c)) {
		result.append('_');
	    }
	    result.append(Character.toUpperCase(c));
	}

	return result.toString();
    }

}
