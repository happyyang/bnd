package aQute.bnd.osgi;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.osgi.annotation.versioning.ProviderType;

import aQute.lib.strings.Strings;
import aQute.service.reporter.Reporter;

/**
 * Type caused coupling to build package and was never locally used. This class
 * was moved to the aQute.bnd.build package
 */
@Deprecated()
@ProviderType
public class Classpath {

	public interface ClassVisitor {
		boolean visit(Clazz clazz) throws Exception;
	}

	List<File>	entries	= new ArrayList<File>();

	public Classpath(Reporter project, String name) {}

	public void add(Collection<Object> testpath) throws Exception {
		throw new UnsupportedOperationException();
	}

	// public void add(Collection<Container> testpath) throws Exception {
	// for (Container c : Container.flatten(testpath)) {
	// if (c.getError() != null) {
	// project.error("Adding %s to %s, got error: %s", c, name, c.getError());
	// } else {
	// entries.add(c.getFile().getAbsoluteFile());
	// }
	// }
	// }

	public List<File> getEntries() {
		return entries;
	}

	/**
	 * Visit each class on the class path.
	 * 
	 * @param visitor the visitor
	 */
	public void visit(ClassVisitor visitor) throws Exception {
		Analyzer analyzer = new Analyzer();
		try {
			for (File f : entries) {
				Jar jar = new Jar(f);
				try {
					for (String path : jar.getResources().keySet()) {
						if (path.endsWith(".class")) {
							Resource r = jar.getResource(path);
							Clazz c = new Clazz(analyzer, path, r);
							c.parseClassFile();
							visitor.visit(c);
						}
					}
				} finally {
					jar.close();
				}
			}
		} finally {
			analyzer.close();
		}
	}

	public String toString() {
		return Strings.join(File.pathSeparator, entries);
	}
}
