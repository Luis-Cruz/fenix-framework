package pt.ist.fenixframework.dml.maven;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;

import org.apache.commons.lang.StringUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;

import pt.ist.fenixframework.core.DmlFile;
import pt.ist.fenixframework.core.Project;
import pt.ist.fenixframework.core.exception.ProjectException;

public class DmlMojoUtils {
    public static Project getProject(MavenProject project, File srcDirectoryFile, List<URL> dmlFiles)
	    throws IOException, ProjectException, MalformedURLException {
	List<Project> dependencies = new ArrayList<Project>();

	for (Artifact artifact : project.getDependencyArtifacts()) {
	    String absolutePath = artifact.getFile().getAbsolutePath();
	    JarFile jarFile = new JarFile(absolutePath);
	    if (jarFile.getJarEntry(artifact.getArtifactId() + "/project.properties") != null) {
		dependencies.add(Project.fromName(artifact.getArtifactId()));
	    }
	}

	List<DmlFile> dmls = new ArrayList<DmlFile>();

	for (URL url : dmlFiles) {
	    URL srcFolder = srcDirectoryFile.toURI().toURL();
	    if (url.toExternalForm().contains(srcFolder.toExternalForm())) {
		dmls.add(new DmlFile(url, StringUtils.removeStart(url.toExternalForm(), srcFolder.toExternalForm())));
	    } else {
		dmls.add(new DmlFile(url, null));
	    }
	}
	return new Project(project.getArtifactId(), dmls, dependencies);
    }

    public static URLClassLoader augmentClassLoader(Log log, MavenProject project) {
	List<String> classpathElements = null;
	try {
	    classpathElements = project.getCompileClasspathElements();
	} catch (DependencyResolutionRequiredException e) {
	    log.error(e);
	}

	URL[] classesURL = new URL[classpathElements.size()];
	int i = 0;

	for (String path : classpathElements) {
	    try {
		classesURL[i++] = new File(path).toURI().toURL();
	    } catch (MalformedURLException e) {
		log.error(e);
	    }
	}

	URLClassLoader loader = new URLClassLoader(classesURL, Thread.currentThread().getContextClassLoader());
	Thread.currentThread().setContextClassLoader(loader);
	return loader;
    }

}
