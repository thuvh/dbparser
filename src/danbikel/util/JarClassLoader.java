package danbikel.util;

import java.net.URL;
import java.net.URLClassLoader;
import java.net.JarURLConnection;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.InvocationTargetException;
import java.util.jar.Attributes;
import java.io.IOException;

/**
 * A class loader for loading a jar file from any URL.
 * Modified from a version taken from Sun's Java website
 * (<a target="_blank" href="http://developer.java.sun.com/developer/Books/JAR/api/jarclassloader.html">The JarClassLoader Class</a>).
 */
public class JarClassLoader extends URLClassLoader {
    private URL url;

    /**
     * Creates a new JarClassLoader for the specified URL.
     *
     * @param url the url of the jar file
     */
    public JarClassLoader(URL url) {
	super(new URL[] { url });
	this.url = url;
    }

    /**
     * Creates a new JarClassLoader for the specified URL and parent class
     * loader.
     *
     * @param url the url of the jar file
     * @param parent the parent class loader for delegation
     */
    public JarClassLoader(URL url, ClassLoader parent) {
	super(new URL[] { url }, parent);
	this.url = url;
    }

    /**
     * Returns the name of the jar file main class, or null if
     * no "Main-Class" manifest attributes was defined.
     */
    public String getMainClassName() throws IOException {
	URL u = new URL("jar", "", url + "!/");
	JarURLConnection uc = (JarURLConnection)u.openConnection();
	Attributes attr = uc.getMainAttributes();
	return attr != null ? attr.getValue(Attributes.Name.MAIN_CLASS) : null;
    }

    /**
     * Invokes the application in this jar file given the name of the
     * main class and an array of arguments. The class must define a
     * static method "main" which takes an array of String arguemtns
     * and is of return type "void".
     *
     * @param name the name of the main class
     * @param args the arguments for the application
     * @exception ClassNotFoundException if the specified class could not
     *            be found
     * @exception NoSuchMethodException if the specified class does not
     *            contain a "main" method
     * @exception InvocationTargetException if the application raised an
     *            exception
     */
    public void invokeClass(String name, String[] args)
	throws ClassNotFoundException,
	       NoSuchMethodException,
	       InvocationTargetException
    {
	Class c = loadClass(name);
	Method m = c.getMethod("main", new Class[] { args.getClass() });
	m.setAccessible(true);
	int mods = m.getModifiers();
	if (m.getReturnType() != void.class || !Modifier.isStatic(mods) ||
	    !Modifier.isPublic(mods)) {
	    throw new NoSuchMethodException("main");
	}
	try {
	    m.invoke(null, new Object[] { args });
	} catch (IllegalAccessException e) {
	    // This should not happen, as we have disabled access checks
	}
    }

}
