package bcelToSqlite;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;

import org.apache.bcel.Const;
import org.apache.bcel.classfile.AnnotationEntry;
import org.apache.bcel.classfile.ClassFormatException;
import org.apache.bcel.classfile.ClassParser;
import org.apache.bcel.classfile.Constant;
import org.apache.bcel.classfile.ConstantPool;
import org.apache.bcel.classfile.Field;
import org.apache.bcel.classfile.JavaClass;
import org.apache.bcel.classfile.Utility;
import org.apache.bcel.generic.Type;
import org.apache.bcel.util.ByteSequence;

public class BcelToSqlite {
	Connection connection = null;
	PreparedStatement pstmt = null;
	int nextClassId = 1;
	int nextMethodId = 0x10000001;
	int nextAnnotationId = 0x20000001;

	// https://hondou.homedns.org/pukiwiki/index.php?JavaSE%20BCEL
	public static void main(String args[]) throws Exception
	{
		String srcPath;
		srcPath = "C:\\pleiades201904\\workspace\\bcelToSqlite\\lib\\bcel-6.3.1.jar";
        BcelToSqlite thisClass = new BcelToSqlite();
        thisClass.startWalk(new File(srcPath));
        System.out.println(srcPath + " -> output.sqlite");
	}

	private void executeSql(String sql) throws SQLException {
		pstmt = connection.prepareStatement(sql);
		pstmt.executeUpdate();
		pstmt.close();
		pstmt = null;
	}
	private void executeSql(String sql, Object args[]) throws SQLException {
		pstmt = connection.prepareStatement(sql);
		int ix = 1;
		for (Object obj : args) {
			try {
				pstmt.setInt(ix, (int)Integer.parseInt(obj.toString()));
			} catch (NumberFormatException ex) {
				pstmt.setString(ix, obj.toString());
			}

			++ix;
		}
		pstmt.executeUpdate();
		pstmt.close();
		pstmt = null;
	}

	private void startWalk(File path) throws Exception {
		try {
			connection = DriverManager.getConnection("jdbc:sqlite:output.sqlite");
			connection.setAutoCommit(true);
			executeSql("drop table if exists class");
			executeSql("create table class (id int primary key, name string , access_flags int, super_class_name string)");

			executeSql("drop table if exists interface");
			executeSql("create table interface (class_id int, interface_name string)");

			executeSql("drop index if exists index_interface");
			executeSql("create index index_interface on interface(class_id)");

			executeSql("drop table if exists method");
			executeSql("create table method (id int primary key, class_id int, name string, fullname string, access_flag int, return_type string, byte_code string)");

			executeSql("drop table if exists method_parameter");
			executeSql("create table method_parameter (method_id int, seq int, param_type string)");

			executeSql("drop index if exists index_method_parameter");
			executeSql("create index index_method_parameter on method_parameter(method_id)");

			executeSql("drop table if exists method_depend");
			executeSql("create table method_depend (method_id int, called_method string, opecode int)");

			executeSql("drop index if exists index_method_depend");
			executeSql("create index index_method_depend on method_depend(method_id)");

			executeSql("drop table if exists field");
			executeSql("create table field (id int primary key, class_id int, name string, fullname string, access_flag int, type string)");

			executeSql("drop table if exists anotation");
			executeSql("create table anotation (id int primary key, refid int, type string)");

			executeSql("drop index if exists index_anotation");
			executeSql("create index index_anotation on anotation(refid)");

			connection.setAutoCommit(false);
			dirWalk(path);
	        connection.commit();

		}
		finally
		{
			if(pstmt != null)
			{
				pstmt.close();
			}
			if(connection != null)
			{
				connection.close();
			}
		}
	}
    private void dirWalk(File path) throws Exception {
    	if (path.isDirectory()) {
    		for (File child : path.listFiles()) {
    			dirWalk(child);
	        }
    	} else if (path.getName().endsWith("jar") || path.getName().endsWith("zip")) {
    		jarWalk(path);
    	} else if (path.getName().endsWith("class")) {
    		JavaClass javaClass = new ClassParser(path.getAbsolutePath()).parse();
	        classWalk(javaClass);
    	}
    }

    private void jarWalk(File jarFile) throws Exception {
        try (JarInputStream jarIn = new JarInputStream(new FileInputStream(jarFile));) {
            JarEntry entry;
            while ((entry = jarIn.getNextJarEntry()) != null) {
                if (!entry.isDirectory()) {
                    String fileName = entry.getName();
                    if (fileName.endsWith("class")) {
                        JavaClass javaClass = new ClassParser(jarFile.getAbsolutePath(), fileName).parse();
                        classWalk(javaClass);
                    }
                }
            }
        }
    }

    private void classWalk(final JavaClass javaClass) throws SQLException, ClassNotFoundException, IOException {
    	System.out.println(javaClass.getClassName());

    	executeSql(
    		"insert into class values(?, ?, ?, ?)",
    		new Object[] {
    			nextClassId,
    			javaClass.getClassName(),
    			javaClass.getAccessFlags(),
    			javaClass.getSuperclassName()
    		}
    	);

    	// メソッドの取得
        final org.apache.bcel.classfile.Method[] methods = javaClass.getMethods();
        for (org.apache.bcel.classfile.Method method : methods) {
            methodWalk(nextClassId, javaClass, method);
        }

        Field[] fields = javaClass.getFields();
        for (Field field : fields) {
            fieldWalk(nextClassId, javaClass, field);
        }

        // インターフェイスの取得
        for (JavaClass i : javaClass.getAllInterfaces()) {
        	if (i.getClassName().equals(javaClass.getClassName())) {
        		continue;
        	}
			executeSql(
				"insert into interface values(?, ?)",
				new Object[] {
						nextClassId,
					i.getClassName()
				}
			);
        }

        // アノテーション
        anotationWalk(nextClassId, javaClass.getAnnotationEntries());

        if (nextClassId % 500 == 0) {
        	connection.commit();
        }

        // コミット
        ++nextClassId;
    }

    private void anotationWalk(final int refId, final AnnotationEntry[] annotations) throws SQLException {
        for (AnnotationEntry a : annotations) {
        	executeSql(
            		"insert into anotation values(?, ?, ?)",
            		new Object[] {
            				nextAnnotationId,
            				refId,
            				a.getAnnotationType()
            		}
            	);
        }
        ++nextAnnotationId;

    }

    private void methodWalk(final int classId, final JavaClass javaClass, final org.apache.bcel.classfile.Method method) throws SQLException, IOException {
		String code = getCode(method);
    	executeSql(
    		"insert into method values(?, ?,  ?, ?, ?, ?, ?)",
    		new Object[] {
    				nextMethodId,
    				classId,
    				method.getName(),
    				javaClass.getClassName() + "." + method.getName() + " " + method.getSignature(),
    				method.getAccessFlags(),
    				method.getReturnType().toString(),
    				code
    			}
    	);

		int seq = 1;
		for(Type p : method.getArgumentTypes()) {
			executeSql(
				"insert into method_parameter values(?, ?, ?)",
				new Object[] {
					nextMethodId,
					seq,
					p.toString()
				}
			);
			++seq;
		}
		if (method.getCode() != null) {
			ByteSequence stream = new ByteSequence(method.getCode().getCode());
			for (int i = 0; stream.available() > 0 ; i++) {
				analyzeCode(nextMethodId, stream, method.getConstantPool());
			}
		}


        // アノテーション
        anotationWalk(nextMethodId, method.getAnnotationEntries());

		++nextMethodId;
    }

    private void fieldWalk(final int classId, final JavaClass javaClass, final org.apache.bcel.classfile.Field field) throws SQLException, IOException {

    	executeSql(
    		"insert into field values(?, ?, ?, ?, ?, ?)",
    		new Object[] {
    				nextMethodId,
    				classId,
    				field.getName(),
    				javaClass.getClassName() + "." + field.getName() + " " + field.getSignature(),
    				field.getAccessFlags(),
    				field.getType().toString()
    			}
    	);

        // アノテーション
        anotationWalk(nextMethodId, field.getAnnotationEntries());

		++nextMethodId;
    }

    private  boolean wide = false; /* The `WIDE' instruction is used in the
     * byte code to allow 16-bit wide indices
     * for local variables. This opcode
     * precedes an `ILOAD', e.g.. The opcode
     * immediately following takes an extra
     * byte which is combined with the
     * following byte to form a
     * 16-bit value.
     */

    /**
     * 以下参考に実装
     * commons-bcel/src/main/java/org/apache/bcel/classfile/Utility.java
     * codeToString
     * @param bytes
     * @param constant_pool
     * @throws IOException
     * @throws SQLException
     * @throws ClassFormatException
     */
    public  void analyzeCode(final int methodId,  final ByteSequence bytes, final ConstantPool constant_pool) throws IOException, ClassFormatException, SQLException {
        final short opcode = (short) bytes.readUnsignedByte();
        int default_offset = 0;
        int low;
        int high;
        int npairs;
        int index;
        int vindex;
        int constant;
        int[] match;
        int[] jump_table;
        int no_pad_bytes = 0;
        int offset;
        final boolean verbose = true;
        final StringBuilder buf = new StringBuilder(Const.getOpcodeName(opcode));
        /* Special case: Skip (0-3) padding bytes, i.e., the
         * following bytes are 4-byte-aligned
         */
        if ((opcode == Const.TABLESWITCH) || (opcode == Const.LOOKUPSWITCH)) {
            final int remainder = bytes.getIndex() % 4;
            no_pad_bytes = (remainder == 0) ? 0 : 4 - remainder;
            for (int i = 0; i < no_pad_bytes; i++) {
                byte b;
                if ((b = bytes.readByte()) != 0) {
                    System.err.println("Warning: Padding byte != 0 in "
                            + Const.getOpcodeName(opcode) + ":" + b);
                }
            }
            // Both cases have a field default_offset in common
            default_offset = bytes.readInt();
        }
        switch (opcode) {
            /* Table switch has variable length arguments.
             */
            case Const.TABLESWITCH:
                low = bytes.readInt();
                high = bytes.readInt();
                offset = bytes.getIndex() - 12 - no_pad_bytes - 1;
                default_offset += offset;
                buf.append("\tdefault = ").append(default_offset).append(", low = ").append(low)
                        .append(", high = ").append(high).append("(");
                jump_table = new int[high - low + 1];
                for (int i = 0; i < jump_table.length; i++) {
                    jump_table[i] = offset + bytes.readInt();
                    buf.append(jump_table[i]);
                    if (i < jump_table.length - 1) {
                        buf.append(", ");
                    }
                }
                buf.append(")");
                break;
            /* Lookup switch has variable length arguments.
             */
            case Const.LOOKUPSWITCH: {
                npairs = bytes.readInt();
                offset = bytes.getIndex() - 8 - no_pad_bytes - 1;
                match = new int[npairs];
                jump_table = new int[npairs];
                default_offset += offset;
                buf.append("\tdefault = ").append(default_offset).append(", npairs = ").append(
                        npairs).append(" (");
                for (int i = 0; i < npairs; i++) {
                    match[i] = bytes.readInt();
                    jump_table[i] = offset + bytes.readInt();
                    buf.append("(").append(match[i]).append(", ").append(jump_table[i]).append(")");
                    if (i < npairs - 1) {
                        buf.append(", ");
                    }
                }
                buf.append(")");
            }
                break;
            /* Two address bytes + offset from start of byte stream form the
             * jump target
             */
            case Const.GOTO:
            case Const.IFEQ:
            case Const.IFGE:
            case Const.IFGT:
            case Const.IFLE:
            case Const.IFLT:
            case Const.JSR:
            case Const.IFNE:
            case Const.IFNONNULL:
            case Const.IFNULL:
            case Const.IF_ACMPEQ:
            case Const.IF_ACMPNE:
            case Const.IF_ICMPEQ:
            case Const.IF_ICMPGE:
            case Const.IF_ICMPGT:
            case Const.IF_ICMPLE:
            case Const.IF_ICMPLT:
            case Const.IF_ICMPNE:
                buf.append("\t\t#").append((bytes.getIndex() - 1) + bytes.readShort());
                break;
            /* 32-bit wide jumps
             */
            case Const.GOTO_W:
            case Const.JSR_W:
                buf.append("\t\t#").append((bytes.getIndex() - 1) + bytes.readInt());
                break;
            /* Index byte references local variable (register)
             */
            case Const.ALOAD:
            case Const.ASTORE:
            case Const.DLOAD:
            case Const.DSTORE:
            case Const.FLOAD:
            case Const.FSTORE:
            case Const.ILOAD:
            case Const.ISTORE:
            case Const.LLOAD:
            case Const.LSTORE:
            case Const.RET:
                if (wide) {
                    vindex = bytes.readUnsignedShort();
                    wide = false; // Clear flag
                } else {
                    vindex = bytes.readUnsignedByte();
                }
                buf.append("\t\t%").append(vindex);
                break;
            /*
             * Remember wide byte which is used to form a 16-bit address in the
             * following instruction. Relies on that the method is called again with
             * the following opcode.
             */
            case Const.WIDE:
                wide = true;
                buf.append("\t(wide)");
                break;
            /* Array of basic type.
             */
            case Const.NEWARRAY:
                buf.append("\t\t<").append(Const.getTypeName(bytes.readByte())).append(">");
                break;
            /* Access object/class fields.
             */
            case Const.GETFIELD:
            case Const.GETSTATIC:
            case Const.PUTFIELD:
            case Const.PUTSTATIC:
                index = bytes.readUnsignedShort();
                buf.append("\t\t").append(
                        constant_pool.constantToString(index, Const.CONSTANT_Fieldref)).append(
                        verbose ? " (" + index + ")" : "");

                executeSql(
                    	"insert into method_depend values(?,?,?)",
                    	new Object[] {
                    		methodId,
                    		constant_pool.constantToString(index, Const.CONSTANT_Fieldref),
                    		opcode
                    	}
                    );
                break;
            /* Operands are references to classes in constant pool
             */
            case Const.NEW:
            case Const.CHECKCAST:
                buf.append("\t");
                //$FALL-THROUGH$
            case Const.INSTANCEOF:
                index = bytes.readUnsignedShort();
                buf.append("\t<").append(
                        constant_pool.constantToString(index, Const.CONSTANT_Class))
                        .append(">").append(verbose ? " (" + index + ")" : "");

                executeSql(
                    	"insert into method_depend values(?,?,?)",
                    	new Object[] {
                    		methodId,
                    		constant_pool.constantToString(index, Const.CONSTANT_Class),
                    		opcode
                    	}
                    );
                break;
            /* Operands are references to methods in constant pool
             */
            case Const.INVOKESPECIAL:
            case Const.INVOKESTATIC:
                index = bytes.readUnsignedShort();
                final Constant c = constant_pool.getConstant(index);
                // With Java8 operand may be either a CONSTANT_Methodref
                // or a CONSTANT_InterfaceMethodref.   (markro)
                buf.append("\t").append(
                        constant_pool.constantToString(index, c.getTag()))
                        .append(verbose ? " (" + index + ")" : "");
                executeSql(
                	"insert into method_depend values(?,?,?)",
                	new Object[] {
                		methodId,
                		constant_pool.constantToString(index, c.getTag()),
                		opcode
                	}
                );
                break;
            case Const.INVOKEVIRTUAL:
                index = bytes.readUnsignedShort();
                buf.append("\t").append(
                        constant_pool.constantToString(index, Const.CONSTANT_Methodref))
                        .append(verbose ? " (" + index + ")" : "");

                executeSql(
                    	"insert into method_depend values(?,?,?)",
                    	new Object[] {
                    		methodId,
                    		constant_pool.constantToString(index, Const.CONSTANT_Methodref),
                    		opcode
                    	}
                    );
                break;
            case Const.INVOKEINTERFACE:
                index = bytes.readUnsignedShort();
                final int nargs = bytes.readUnsignedByte(); // historical, redundant
                buf.append("\t").append(
                        constant_pool
                                .constantToString(index, Const.CONSTANT_InterfaceMethodref))
                        .append(verbose ? " (" + index + ")\t" : "").append(nargs).append("\t")
                        .append(bytes.readUnsignedByte()); // Last byte is a reserved space
                executeSql(
                    	"insert into method_depend values(?,?,?)",
                    	new Object[] {
                    		methodId,
                    		constant_pool.constantToString(index, Const.CONSTANT_InterfaceMethodref),
                    		opcode
                    	}
                    );
                break;
            case Const.INVOKEDYNAMIC:
                index = bytes.readUnsignedShort();
                buf.append("\t").append(
                        constant_pool
                                .constantToString(index, Const.CONSTANT_InvokeDynamic))
                        .append(verbose ? " (" + index + ")\t" : "")
                        .append(bytes.readUnsignedByte())  // Thrid byte is a reserved space
                        .append(bytes.readUnsignedByte()); // Last byte is a reserved space

                executeSql(
                    	"insert into method_depend values(?,?,?)",
                    	new Object[] {
                    		methodId,
                    		constant_pool.constantToString(index, Const.CONSTANT_InvokeDynamic),
                    		opcode
                    	}
                    );
                break;
            /* Operands are references to items in constant pool
             */
            case Const.LDC_W:
            case Const.LDC2_W:
                index = bytes.readUnsignedShort();
                buf.append("\t\t").append(
                        constant_pool.constantToString(index, constant_pool.getConstant(index)
                                .getTag())).append(verbose ? " (" + index + ")" : "");
                break;
            case Const.LDC:
                index = bytes.readUnsignedByte();
                buf.append("\t\t").append(
                        constant_pool.constantToString(index, constant_pool.getConstant(index)
                                .getTag())).append(verbose ? " (" + index + ")" : "");
                break;
            /* Array of references.
             */
            case Const.ANEWARRAY:
                index = bytes.readUnsignedShort();
                buf.append("\t\t<").append(
                		Utility.compactClassName(constant_pool.getConstantString(index,
                                Const.CONSTANT_Class), false)).append(">").append(
                        verbose ? " (" + index + ")" : "");
                break;
            /* Multidimensional array of references.
             */
            case Const.MULTIANEWARRAY: {
                index = bytes.readUnsignedShort();
                final int dimensions = bytes.readUnsignedByte();
                buf.append("\t<").append(
                		Utility.compactClassName(constant_pool.getConstantString(index,
                                Const.CONSTANT_Class), false)).append(">\t").append(dimensions)
                        .append(verbose ? " (" + index + ")" : "");
            }
                break;
            /* Increment local variable.
             */
            case Const.IINC:
                if (wide) {
                    vindex = bytes.readUnsignedShort();
                    constant = bytes.readShort();
                    wide = false;
                } else {
                    vindex = bytes.readUnsignedByte();
                    constant = bytes.readByte();
                }
                buf.append("\t\t%").append(vindex).append("\t").append(constant);
                break;
            default:
                if (Const.getNoOfOperands(opcode) > 0) {
                    for (int i = 0; i < Const.getOperandTypeCount(opcode); i++) {
                        buf.append("\t\t");
                        switch (Const.getOperandType(opcode, i)) {
                            case Const.T_BYTE:
                                buf.append(bytes.readByte());
                                break;
                            case Const.T_SHORT:
                                buf.append(bytes.readShort());
                                break;
                            case Const.T_INT:
                                buf.append(bytes.readInt());
                                break;
                            default: // Never reached
                                throw new IllegalStateException("Unreachable default case reached!");
                        }
                    }
                }
        }
    }

    private String getCode(org.apache.bcel.classfile.Method method) {
    	if (method.getCode() == null) {
    		return "";
    	}
    	return method.getCode().toString();
    }

}
