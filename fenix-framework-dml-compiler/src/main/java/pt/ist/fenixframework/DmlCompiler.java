package pt.ist.fenixframework;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import pt.ist.fenixframework.dml.CodeGenerator;
import pt.ist.fenixframework.dml.CompilerArgs;
import pt.ist.fenixframework.dml.DmlCompilerException;
import pt.ist.fenixframework.dml.DmlLexer;
import pt.ist.fenixframework.dml.DmlParser;
import pt.ist.fenixframework.dml.DmlTreeParser;
import pt.ist.fenixframework.dml.DomainModel;


import antlr.ANTLRException;
import antlr.collections.AST;

public class DmlCompiler {
    /** Runs the DML compiler
     *
     * This is the main entry point for running the DML compiler, from the command line.  This
     * method will create the {@link CompilerArgs} from the command line parameters, and then invoke
     * {@link DmlCompiler#compile}.
     *
     * @param args All the compiler's parameters
     *
     * @see CompilerArgs
     */
    public static void main(String[] args) throws DmlCompilerException {
	CompilerArgs compArgs = new CompilerArgs(args);
        compile(compArgs);
    }

    /** Runs the DML compiler
     *
     * This is the main entry point for, programmatically, running the DML compiler.  The compiler
     * will first create the domain model, and then run the code generator.
     *
     * @param compArgs All the compiler's parameters
     * @return The {@link DomainModel}
     *
     * @see CompilerArgs
     */
    public static DomainModel compile(CompilerArgs compArgs) throws DmlCompilerException {
        try {
            DomainModel model = getDomainModel(compArgs);
            CodeGenerator generator = compArgs.getCodeGenerator().getConstructor(CompilerArgs.class,
                                                                                 DomainModel.class).newInstance(compArgs, model);
            generator.generateCode();
            generator.generateBackEndId();
            return model;
        } catch (Exception e) {
            throw new DmlCompilerException(e);
        }
    }

    public static DomainModel getDomainModel(CompilerArgs compArgs) throws DmlCompilerException {
        // IMPORTANT: external specs must be first.  The order is important for the DmlCompiler
        List<URL> dmlSpecs = new ArrayList<URL>(compArgs.getExternalDomainSpecs());
        dmlSpecs.addAll(compArgs.getLocalDomainSpecs());
	return getDomainModel(dmlSpecs, false);
    }

    public static DomainModel getDomainModel(List<URL> dmlFilesURLs) throws DmlCompilerException {
	return getDomainModel(dmlFilesURLs, false);
    }

    public static DomainModel getDomainModel(List<URL> dmlFilesURLs, boolean checkForMissingExternals)
        throws DmlCompilerException {
	DmlTreeParser walker = new DmlTreeParser();
	DomainModel model = new DomainModel();

	for (URL dmlFileURL : dmlFilesURLs) {
	    InputStream urlStream = null;
	    DataInputStream in = null;
	    try {
		urlStream = dmlFileURL.openStream();
		in = new DataInputStream(new BufferedInputStream(urlStream));

		DmlLexer lexer = new DmlLexer(in);
		DmlParser parser = new DmlParser(lexer);
		parser.domainDefinitions();
		AST t = parser.getAST();
		// System.out.println(t.toStringTree());

		// ASTFrame fr = new ASTFrame("Tree Viewer", t);
		// fr.setVisible(true);

		walker.domainDefinitions(t, model, dmlFileURL);
		// System.out.println("Model = " + model);
	    } catch (ANTLRException e) {
                throw new DmlCompilerException(e);
	    } catch (IOException ioe) {
		System.err.println("Cannot read " + dmlFileURL + ".  Ignoring it...");
		// System.exit(3);
	    } finally {
		if (in != null) {
		    try {
			in.close();
		    } catch (IOException ioe) {
		    }
		}
		if (urlStream != null) {
		    try {
			urlStream.close();
		    } catch (IOException ioe) {
		    }
		}
	    }
	}

	model.finalizeDomain(checkForMissingExternals);
	return model;
    }
}
