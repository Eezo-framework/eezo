import java.io.File;
import java.lang.ref.WeakReference;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.function.IntConsumer;

/**
 * The hot-reload question for eezo #3, tested directly.
 *
 * Boots a server inside a throwaway URLClassLoader whose parent is the platform
 * loader (so nothing from the application loader is involved), stops it, drops
 * every reference, and asks whether the loader becomes collectable. If it does
 * not, a classloader-swapping dev loop leaks the entire previous application on
 * every edit, and hot reload must fork a JVM instead.
 *
 * args: <bootClass> <bootClassDir> <serverClasspath> <port> <iterations>
 */
public class ClassLoaderSwap {
  public static void main(String[] a) throws Exception {
    String bootClass = a[0];
    String bootDir = a[1];
    String cp = a[2];
    int port = Integer.parseInt(a[3]);
    int iters = Integer.parseInt(a[4]);

    var urls = new java.util.ArrayList<URL>();
    urls.add(new File(bootDir).toURI().toURL());
    for (String p : cp.split(File.pathSeparator)) if (!p.isBlank()) urls.add(new File(p).toURI().toURL());
    URL[] arr = urls.toArray(new URL[0]);

    var refs = new java.util.ArrayList<WeakReference<ClassLoader>>();
    for (int i = 0; i < iters; i++) {
      refs.add(cycle(arr, bootClass, port));
      System.gc();
      Thread.sleep(200);
      System.gc();
      Thread.sleep(200);
      long alive = refs.stream().filter(r -> r.get() != null).count();
      System.out.printf("%s iteration %d: loaders still reachable = %d / %d, threads=%d%n",
          bootClass, i + 1, alive, refs.size(),
          java.lang.management.ManagementFactory.getThreadMXBean().getThreadCount());
    }

    for (int g = 0; g < 6; g++) { System.gc(); Thread.sleep(400); }
    long alive = refs.stream().filter(r -> r.get() != null).count();
    System.out.printf("%s FINAL: loaders still reachable after aggressive GC = %d / %d%n",
        bootClass, alive, refs.size());
    if (alive > 0) {
      System.out.println(bootClass + " => classloader RETAINED: classloader-swap hot reload leaks.");
    } else {
      System.out.println(bootClass + " => classloader RELEASED: classloader-swap hot reload is viable.");
    }
    System.exit(0);
  }

  /** Isolated so no local variable on main's frame keeps the loader alive. */
  static WeakReference<ClassLoader> cycle(URL[] urls, String bootClass, int port) throws Exception {
    URLClassLoader cl = new URLClassLoader("swap", urls, ClassLoader.getPlatformClassLoader());
    IntConsumer boot = (IntConsumer) cl.loadClass(bootClass).getDeclaredConstructor().newInstance();
    // Servers that discover providers via ServiceLoader read the thread context
    // classloader, so a hot-reload harness has to set it. Helidon does not boot
    // at all without this; Jetty does not need it.
    ClassLoader prev = Thread.currentThread().getContextClassLoader();
    Thread.currentThread().setContextClassLoader(cl);
    try {
      boot.accept(port);
    } finally {
      Thread.currentThread().setContextClassLoader(prev);
    }
    cl.close();
    return new WeakReference<>(cl);
  }
}
