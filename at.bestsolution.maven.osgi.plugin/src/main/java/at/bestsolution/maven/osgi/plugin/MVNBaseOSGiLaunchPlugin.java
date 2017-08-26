package at.bestsolution.maven.osgi.plugin;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Collectors;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

public abstract class MVNBaseOSGiLaunchPlugin extends AbstractMojo {
	private static final String LF = System.getProperty("line.separator");
	
	@Parameter
	protected List<String> programArguments;
	
	@Parameter
	protected Properties vmProperties;
	
	@Parameter
	protected Map<String, Integer> startLevels;
	
	@Parameter(defaultValue = "${project}", required = true, readonly = true)
	protected MavenProject project;
	
	@Parameter(defaultValue = "${project.build.directory}")
    private String projectBuildDir;

	@Parameter(defaultValue = "${project.build.finalName}")
    private String filename;
	
	@Parameter
	private boolean debug;
	
	private String toReferenceURL(Bundle element, boolean project) throws IOException {
		StringBuilder w = new StringBuilder();
		w.append("reference\\:file\\:" + element.path.toString());

		if (element.startLevel != null) {
			w.append("@" + element.startLevel + "\\:start");
		} else {
			w.append("@start");
		}
		return w.toString();
	}
	
	protected Path generateConfigIni(MavenProject project) {
		Set<Bundle> bundles = project
				.getArtifacts()
				.stream()
				.map( this::map )
				.collect(Collectors.toSet());
		
		
		if( project.getPackaging().equals("jar") ) {
			Path binary = project.getArtifact().getFile().toPath();
			bundles.add(new Bundle(getManifest(binary),binary));			
		}
		
		Path p = Paths.get(System.getProperty("java.io.tmpdir")).resolve(project.getArtifactId()).resolve("configuration");

		Optional<Bundle> simpleConfigurator = bundles.stream()
				.filter(b -> "org.eclipse.equinox.simpleconfigurator".equals(b.symbolicName)).findFirst();

		Optional<Bundle> equinox = bundles.stream().filter(b -> "org.eclipse.osgi".equals(b.symbolicName))
				.findFirst();

		try {
			Files.createDirectories(p);
		} catch (IOException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}
		
		if (simpleConfigurator.isPresent()) {
			Path configIni = p.resolve("config.ini");
			try (BufferedWriter writer = Files.newBufferedWriter(configIni, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
				Path bundlesInfo = generateBundlesInfo(p, bundles);

				writer.append("osgi.bundles=" + toReferenceURL(simpleConfigurator.get(), false));
				writer.append(LF);
				writer.append("osgi.bundles.defaultStartLevel=4");
				writer.append(LF);
				writer.append("osgi.install.area=file\\:" + p.getParent().resolve("install").toString());
				writer.append(LF);
				writer.append("osgi.framework=file\\:" + equinox.get().path.toString());
				writer.append(LF);
				writer.append("eclipse.p2.data.area=@config.dir/.p2");
				writer.append(LF);
				writer.append("org.eclipse.equinox.simpleconfigurator.configUrl=file\\:"
						+ bundlesInfo.toAbsolutePath().toString());
				writer.append(LF);
				writer.append("osgi.configuration.cascaded=false");
				writer.append(LF);
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		} else {
			throw new RuntimeException("Only 'org.eclipse.equinox.simpleconfigurator' is supported");
		}
		
		return p;
	}
	
	private Path generateBundlesInfo(Path configurationDir, Set<Bundle> bundles) {
		Path bundleInfo = configurationDir.resolve("org.eclipse.equinox.simpleconfigurator").resolve("bundles.info");
		try {
			Files.createDirectories(bundleInfo.getParent());
		} catch (IOException e) {
			throw new RuntimeException(e);
		}

		try (BufferedWriter writer = Files.newBufferedWriter(bundleInfo, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
			writer.append("#encoding=UTF-8");
			writer.append(LF);
			writer.append("#version=1");
			writer.append(LF);

			for (Bundle b : bundles) {
				if( "org.eclipse.osgi".equals(b.symbolicName) ) {
					continue;
				}
				writer.append(b.symbolicName);
				writer.append("," + b.version);
				writer.append(",file:" + b.path.toAbsolutePath().toString());
				writer.append("," + b.startLevel); // Start Level
				writer.append("," + b.autoStart); // Auto-Start
				writer.append(LF);
			}

		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		return bundleInfo;
	}
	
	private Bundle map(Artifact a) {
		try {
			return _map(a);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
	
	private static String bundleName(Manifest m) {
		String name = m.getMainAttributes().getValue("Bundle-SymbolicName");
		return name.split(";")[0];
	}
	
	private Bundle _map(Artifact a) throws IOException {
		File file = a.getFile();
		Path path = file.toPath();
		Manifest m = getManifest(path);
		return new Bundle(m, path);
	}
	
	private Manifest getManifest(Path p) {
		if (Files.isDirectory(p)) {
			try (InputStream in = Files
					.newInputStream(p.resolve("META-INF").resolve("MANIFEST.MF"))) {
				return new Manifest(in);
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		} else {
			try (JarFile f = new JarFile(p.toFile())) {
				return f.getManifest();
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
	}
	
	private Integer getStartLevel(Manifest m) {
		String name = bundleName(m);
		if( startLevels != null ) {
			return startLevels.get(name);
		} else {
			switch (name) {
			case "org.eclipse.core.runtime":
				return 4;
			case "org.eclipse.equinox.common":
				return 2;
			case "org.eclipse.equinox.ds":
				return 2;
			case "org.eclipse.equinox.event":
				return 2;
			case "org.eclipse.equinox.simpleconfigurator":
				return 1;
			case "org.eclipse.osgi":
				return -1;
			default:
				return null;
			}			
		}
	}
	
	public class Bundle {
		public final String symbolicName;
		public final String version;
		public final Integer startLevel;
		public final Path path;
		public final boolean autoStart;
		
		public Bundle(Manifest m, Path path) {
			this( bundleName(m), m.getMainAttributes().getValue("Bundle-Version"), getStartLevel(m), path, getStartLevel(m) != null);
		}
		
		public Bundle(String symbolicName, String version, Integer startLevel, Path path, boolean autoStart) {
			this.symbolicName = symbolicName;
			this.version = version;
			this.startLevel = startLevel == null ? 4 : startLevel;
			this.path = path;
			this.autoStart = autoStart;
		}
	}
}