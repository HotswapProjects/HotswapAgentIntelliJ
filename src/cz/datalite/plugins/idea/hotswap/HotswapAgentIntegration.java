package cz.datalite.plugins.idea.hotswap;

import com.intellij.execution.RunManager;
import com.intellij.execution.application.ApplicationConfiguration;
import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.execution.configurations.ConfigurationType;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.impl.RunManagerImpl;
import com.intellij.ide.plugins.IdeaPluginDescriptor;
import com.intellij.ide.plugins.PluginManager;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationListener;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.ProjectJdkTable;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.projectRoots.SdkTypeId;
import com.intellij.openapi.startup.StartupActivity;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.popup.Balloon;
import com.intellij.openapi.ui.popup.BalloonBuilder;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.io.FileUtilRt;
import com.intellij.util.Consumer;
import com.intellij.util.io.UrlConnectionUtil;
import com.intellij.util.net.HttpConfigurable;
import com.intellij.util.net.NetUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.idea.maven.execution.MavenRunConfiguration;
import org.jetbrains.idea.maven.execution.MavenRunner;
import org.jetbrains.idea.maven.execution.MavenRunnerSettings;
import org.kohsuke.github.GHRelease;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;

import javax.swing.event.HyperlinkEvent;
import java.io.*;
import java.net.HttpURLConnection;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;


/**
 * Integrace HotswapAgenta
 */
public class HotswapAgentIntegration implements StartupActivity
{
    /**
     * Klič, který určuje aktuální verzi hotswap agenta
     */
    public final static String AGENT_VERSION_KEY = "cz.datalite.plugins.idea.hotswap.agent.version" ;

    /**
     *  Název vlastníka GITHUB repositáře
     */
    public final static String GITHUB_OWNER = "HotswapProjects" ;

    /**
     *  Název projektu
     */
    public final static String GITHUB_PROJECT = "HotswapAgent" ;

    /**
     * Název repositáře
     */
    public final static String GITHUB_REPOSITORY = GITHUB_OWNER + "/" + GITHUB_PROJECT ;

    /**
     * URL pro stažení
     */
    public final static String ZIP_URL_PATTERN = "https://github.com/" + GITHUB_OWNER + "/" + GITHUB_PROJECT + "/releases/download/%s/" + GITHUB_PROJECT + "-%s.zip" ;



    /**
     * Klíč pro určení alternativního JVM
     */
    public final static String ALT_JVM = "-XXaltjvm" ;

    /**
     * Klíč pro přidání agenta
     */
    public final static String JAVA_AGENT = "-javaagent" ;

    /**
     * Aktuální popis pluginu
     */
    private IdeaPluginDescriptor pluginDescriptor;

    @Override
    public void runActivity(final @NotNull Project project)
    {
        final Application application = ApplicationManager.getApplication();
        final String newRelease = getLastRelease() ;
        final String jre = getJrePath() ;

        //Pokud se jedná o 64 bit windows nebo linux
        if ( ( newRelease != null) && (((SystemInfo.isLinux) && (SystemInfo.isAMD64)) || ((SystemInfo.isWindows) && (SystemInfo.is64Bit))))
        {
            if ( jre != null )
            {
                application.executeOnPooledThread(
                        new Runnable()
                        {
                            @Override
                            public void run()
                            {
                                checkForUpdate(project, newRelease, jre);
                            }
                        }
                );
            }
            else
            {
                new Notification(getPluginDescriptor().getName(), getPluginDescriptor().getName(), "Can`t find directory with JRE for store hotswap Agent.", NotificationType.ERROR).notify(project);
            }
        }
        else
        {
           new Notification(getPluginDescriptor().getName(), getPluginDescriptor().getName(), "Hotswap Agent for current OS is unavailable.", NotificationType.WARNING).notify(project);
        }
    }

    /**
     * @return popis pluginu
     */
    private IdeaPluginDescriptor getPluginDescriptor()
    {
        if (pluginDescriptor == null)
        {
            PluginId pluginId = PluginManager.getPluginByClassName(getClass().getName());

            assert pluginId != null;

            pluginDescriptor = PluginManager.getPlugin(pluginId);
        }

        return pluginDescriptor;
    }

    /**
     * Zjištění zda existuje novější verze
     *
     * @param project aktuální projekt
     * @param newRelease nový release
     */
    private void checkForUpdate(final @NotNull Project project, final @NotNull String newRelease, final @NotNull String jre )
    {
        final PropertiesComponent propertiesComponent = PropertiesComponent.getInstance() ;
        final String currentRelease = propertiesComponent.getValue(AGENT_VERSION_KEY, "") ;

        if ( ( ! new File( jre ).exists() ) || ( ! currentRelease.equals( newRelease ) ) )
        {
            if ( "".equals( currentRelease ) )
            {
                installWithAsk(project, new Runnable()
                {
                    @Override
                    public void run()
                    {
                        download(project, newRelease, jre);
                    }
                });
            }
            else
            {
                downloadWithAsk(project, new Runnable()
                {
                    @Override
                    public void run()
                    {
                        download(project, newRelease, jre);
                    }
                });
            }
        }
        else
        {
            patchIfNecessary( project, jre ) ;
        }
    }

    /**
     * Stažení hotswap agenta
     *
     * @param project   aktuální projekt
     * @param newRelease nový release
     */
    private void download(final @NotNull Project project, final @NotNull String newRelease, final @NotNull String jre )
    {
        ProgressManager.getInstance().run( new Task.Backgroundable(project, "Downloading " + getPluginDescriptor().getName(), true)
        {
            @Override
            public void run(@NotNull ProgressIndicator indicator)
            {
                final File tmpFile = new File(FileUtilRt.getTempDirectory(), getPluginDescriptor().getName() + "/" + newRelease + ".zip" ) ;

                FileUtilRt.delete(tmpFile);

                download(project, tmpFile, newRelease, jre, indicator) ;
            }
        });
    }

    /**
     * Zobrazení žádosti o povolení stáhnout novou verzi
     *
     * @param project  aktuální projekt
     * @param callback funkce, která se provede po potrvzení
     */
    public void installWithAsk( @NotNull Project project, @NotNull final Runnable callback )
    {
        if ( Messages.showYesNoDialog( project, "Hotswap Agent", "Do You Want to Download the New Version ?", Messages.getQuestionIcon() ) == Messages.YES )
        {
            callback.run() ;
        }
    }


    /**
     * Zobrazení žádosti o povolení stáhnout novou verzi
     *
     * @param project  aktuální projekt
     * @param callback funkce, která se provede po potrvzení
     */
    public void downloadWithAsk( @NotNull Project project, @NotNull final Runnable callback)
    {
        Notification notification = new Notification(
                getPluginDescriptor().getName(),
                getPluginDescriptor().getName(),
                "New version found <html><a href=''>download</a> it ?",
                NotificationType.INFORMATION,
                new NotificationListener()
                {
                    @Override
                    public void hyperlinkUpdate(@NotNull Notification notification, @NotNull HyperlinkEvent event)
                    {
                        notification.expire();
                        callback.run();
                    }
                }
        );
        notification.notify(project);
    }


    /**
     * @return poslední platné vydání
     */
    private String getLastRelease()
    {
        try
        {
            GitHub github = GitHub.connectAnonymously() ;
            GHRepository repo = github.getRepository(GITHUB_REPOSITORY);

            final List<GHRelease> releases = repo.getReleases();

            return (!releases.isEmpty()) ? releases.get(0).getTagName() : null ;
        }
        catch (IOException e)
        {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Stažení souboru z GITHUB
     *
     * @param project           aktuální projekt
     * @param destination       cílový soubor
     * @param taqName           stahované vydání
     * @param indicator         indikátor průběhu stahování
     */
    private void download( @NotNull final Project project, final @NotNull File destination, final @NotNull String taqName, final @NotNull String jre,  @NotNull final ProgressIndicator indicator )
    {
        execute( project, taqName, new Consumer<HttpURLConnection>()
        {
            @Override
            public void consume(HttpURLConnection connection)
            {
                final int contentLength = connection.getContentLength();

                boolean ok = false;
                InputStream in = null;
                OutputStream out = null;

                try
                {
                    //Surprisingly Dropbox can return instead some 5** error.
                    if (connection.getResponseCode() != HttpURLConnection.HTTP_OK)
                    {
                        error(project, "Error download " + GITHUB_PROJECT + " - Server error\n" + connection.getResponseMessage());

                        return;
                    }

                    in = UrlConnectionUtil.getConnectionInputStreamWithException(connection, indicator);

                    indicator.setIndeterminate(contentLength <= 0);

                    File parent =  destination.getParentFile() ;

                    if ( ! parent.exists() )
                    {
                        info(project, "Create target temp directory: " + parent.getAbsolutePath() ) ;

                        //noinspection ResultOfMethodCallIgnored
                        parent.mkdirs(); //Založení adresářů pokud je potřeba
                    }

                    out = new BufferedOutputStream(new FileOutputStream(destination, false));
                    NetUtils.copyStreamContent(indicator, in, out, contentLength);
                    ok = true;
                }
                catch (IOException e)
                {
                    error( project, e.getMessage() ) ;
                }
                finally
                {
                    if (in != null)
                    {
                        try
                        {
                            in.close();
                        }
                        catch (IOException e)
                        {
                            // Ignore
                        }
                    }
                    if (out != null)
                    {
                        try
                        {
                            out.close();
                        }
                        catch (IOException e)
                        {
                            e.printStackTrace();
                        }
                    }
                }

                if (ok)
                {
                    info(project, String.format("Release '%s' downloaded to '%s'", taqName, destination.getAbsolutePath()));
                    install(project, destination, new File( jre + File.separatorChar ), taqName ) ;

                }

                FileUtilRt.delete( destination ) ;
            }
        } ) ;
    }


    /**
     * Instalace DCEVM a Hotswap agenta
     *
     * @param project       aktuální projekt
     * @param source        zdrojový soubor
     * @param target        cílový adresář
     * @param tagName       instalovaná verze
     */
    private void install( @NotNull Project project, @NotNull File source, @NotNull File target, String tagName )
    {
        info(project, String.format("Install '%s' to '%s'", source.getAbsolutePath(), target.getAbsolutePath()));

        try
        {
            //noinspection ResultOfMethodCallIgnored
            target.mkdirs() ;

            extract(new ZipFile( source ), target, new FilenameFilter()
            {
                @Override
                public boolean accept(File dir, String name)
                {
                    return (
                            ((dir.getName().endsWith("Linux_Amd64bit")) && (SystemInfo.isAMD64) && (SystemInfo.isLinux))
                                    || ((dir.getName().endsWith("Windows 64bit")) && (SystemInfo.is64Bit) && (SystemInfo.isWindows))
                                    || (dir.getName().equals("plugin"))
                                    || (name.equals("HotswapAgent.jar"))
                    );
                }
            }, true);

            patchIfNecessary( project, target.getParent() ) ;

            PropertiesComponent propertiesComponent = PropertiesComponent.getInstance();

            propertiesComponent.setValue( AGENT_VERSION_KEY, tagName ) ;
        }
        catch ( IOException e )
        {
            throw new IllegalStateException( e ) ;
        }
    }

    /**
     *
     * @param zipFile           aktuální soubor
     * @param outputDir         výstupní adresář
     * @param filenameFilter    filtr
     * @param overwrite         příznak zda přepsat existeujicí
     */
    private void extract(final @NotNull ZipFile zipFile, @NotNull File outputDir, @Nullable FilenameFilter filenameFilter, boolean overwrite) throws IOException
    {
        final Enumeration entries = zipFile.entries();

        while ( entries.hasMoreElements() )
        {
            ZipEntry entry = (ZipEntry)entries.nextElement();
            File file = new File(outputDir, entry.getName());

            if (filenameFilter == null || filenameFilter.accept(file.getParentFile(), file.getName()))
            {
                extractEntry(entry, zipFile.getInputStream(entry), outputDir, overwrite);
            }
        }
    }

    /**
     * Extrahování položky ze ZIP souboru
     *
     * @param entry             položka
     * @param inputStream       vstupní data
     * @param outputDir         výstupní adresář
     * @param overwrite         příznak zda přepsat existeujicí
     */
    private void extractEntry(ZipEntry entry, final InputStream inputStream, File outputDir, boolean overwrite) throws IOException
    {
        final boolean isDirectory = entry.isDirectory();
        final String relativeName = entry.getName();
        File file = new File(outputDir, relativeName);

        if ( ! relativeName.startsWith( "plugin" ) )
        {
             file = new File( outputDir, file.getName() ) ;
        }

        if ( ( file.exists() ) && ( ! overwrite ) )
        {
            return;
        }

        FileUtil.createParentDirs(file);

        if (isDirectory)
        {
            //noinspection ResultOfMethodCallIgnored
            file.mkdir() ;
        }
        else
        {
            final BufferedInputStream is = new BufferedInputStream(inputStream);
            final BufferedOutputStream os = new BufferedOutputStream(new FileOutputStream(file));

            try
            {
                FileUtil.copy(is, os);
            }
            finally
            {
                os.close();
                is.close();
            }
        }
    }


    /**
     * @return cesta k JRE
     */
    private String getJrePath()  //TODO: Udělat nějak eleganěnji. Například stahovat vždy cele JRE do alternativního adresáře
    {
        SdkTypeId id = ProjectJdkTable.getInstance().getDefaultSdkType()  ;

        if ( id != null )
        {
            List<Sdk> sdk = ProjectJdkTable.getInstance().getSdksOfType(ProjectJdkTable.getInstance().getDefaultSdkType()) ;

            if ( ! sdk.isEmpty() )
            {
                return modifyForAlternativeJvm( sdk.get(0).getHomePath() + File.separatorChar ) ;
            }
        }

        return getPluginDescriptor().getPath().getAbsolutePath() + File.separatorChar + GITHUB_PROJECT + File.separatorChar ;
    }


    /**
     * Modifikace cesty k JRE
     *
     * @param original      originální cesta k JRE
     * @return modifikovaná cesta
     */
    private String modifyForAlternativeJvm( String original )
    {
        if ( SystemInfo.isLinux )
        {
            if ( SystemInfo.isAMD64 )
            {
                return original + File.separatorChar + "jre" + File.separatorChar + "lib" + File.separatorChar + "amd64" + File.separatorChar + GITHUB_PROJECT + File.separatorChar ;
            }
        }
        else if ( SystemInfo.isWindows )
        {
            return original + File.separatorChar + "jre" + File.separatorChar + "bin" + File.separatorChar + GITHUB_PROJECT + File.separatorChar ;
        }

        return null ;
    }



    /**
     * Modifikace spouštěcí konfigurace pokud je potřeba
     *
     * @param project   aktuální projekt
     */
    private void patchIfNecessary( final @NotNull Project project, final @NotNull String jre )
    {
        configurationTypePatchIfNecessary(project, jre) ;
        configurationPatchIfNecessary( project, jre ) ;
    }

    /**
     * Aplikování patche na budoucí konfigurace
     *
     * @param project       aktuální projekt
     * @param jre           cesta k JVM
     */
    private void configurationPatchIfNecessary( final @NotNull Project project, final @NotNull String jre )
    {
        // Template configuration.
        RunManager runManager = RunManagerImpl.getInstance(project) ;

        //Modifikace akutální konfigurací
        for ( RunConfiguration configuration : runManager.getAllConfigurationsList() )
        {
            if (configuration instanceof ApplicationConfiguration)
            {
                patchConfiguration((ApplicationConfiguration) configuration, jre);
            }
            else if (configuration instanceof MavenRunConfiguration )
            {
                patchConfiguration((MavenRunConfiguration) configuration, jre);
            }
        }
    }

    /**
     * Aplikování patche na budoucí konfigurace
     *
     * @param project       aktuální projekt
     * @param jre           cesta k JVM
     */
    private void configurationTypePatchIfNecessary( final @NotNull Project project, final @NotNull String jre )
    {
        // Template configuration.
        RunManager runManager = RunManagerImpl.getInstance(project) ;

        //Modifikace akutální konfigurací
        for ( ConfigurationType configurationType : runManager.getConfigurationFactories() )
        {
            configurationTypePatchIfNecessary(project, configurationType, jre);
        }
    }

    /**
     * Aplikování patche na budoucí konfigurace
     *
     * @param project               aktuální projekt
     * @param configurationType     typ konfigurace
     * @param jre                   cesta k JVM
     */
    private void configurationTypePatchIfNecessary( final @NotNull Project project, @NotNull ConfigurationType configurationType, final @NotNull String jre )
    {
        RunManager runManager = RunManagerImpl.getInstance(project) ;

        //Modifikace továrních konfigurací pro pozdější použití
        for( ConfigurationFactory factory : configurationType.getConfigurationFactories() )
        {
            RunConfiguration templateApplicationConfig = runManager.getConfigurationTemplate(factory).getConfiguration() ;

            patchTemplateConfiguration(templateApplicationConfig, jre) ;
        }
    }

    /**
     * Odstranění parametru
     *
     * @param name      jméno odstraňovaného parametru včetně pomlčky
     * @param value     řetezec ze kterého se parametr odstraňuje
     *
     * @return hodnota bez odstraňovaného parametru
     */
    private String removeParameters( @NotNull String name, @NotNull String value )
    {
        String vmParameters = value ;

        while ( vmParameters.contains( name ) )
        {
            String prefix = vmParameters.substring( 0, vmParameters.indexOf( name ) ) ;

            vmParameters = vmParameters.substring( vmParameters.indexOf( name ) + name.length() ) ;

            if ( vmParameters.indexOf( " -" ) > 0 )
            {
                vmParameters = prefix + vmParameters.substring(vmParameters.indexOf(" -"));
            }
            else
            {
                vmParameters = prefix ;
            }
        }

        return vmParameters.trim() ;
    }


    /**
     * Změna konfigurace
     *
     * @param original      originální konfigurace
     * @param jre           cesta k JVM
     * @return změněná konfigurace
     */
    private String patchVmParameters( String original, @NotNull String jre )
    {
        String vmParameters = original ;

        if ( vmParameters != null )
        {
            vmParameters = vmParameters.replace( "null" + JAVA_AGENT, JAVA_AGENT ) ;
        }

        if ( vmParameters != null )
        {
            vmParameters = removeParameters( ALT_JVM, vmParameters ) ;
            vmParameters = removeParameters( JAVA_AGENT, vmParameters ) ;
        }

        if ( vmParameters == null )
        {
            vmParameters = ALT_JVM + "=" + jre ;
        }
        else if ( ! vmParameters.contains( ALT_JVM ) )
        {
            vmParameters = vmParameters +  " " + ALT_JVM + "=" + jre ;
        }

        vmParameters = vmParameters +  " " + JAVA_AGENT + ":" + jre + "HotswapAgent.jar" ;

        return vmParameters ;
    }

    /**
     * Nastavení použití Hotswap agenta
     *
     * @param configuration     aktuální konfigurace
     */
    private void patchTemplateConfiguration( RunConfiguration configuration, String jre )
    {
        if ( configuration instanceof ApplicationConfiguration )
        {
            patchConfiguration((ApplicationConfiguration) configuration, jre) ;
        }
        else if ( configuration instanceof MavenRunConfiguration )
        {
            patchConfiguration( (MavenRunConfiguration)configuration, jre ) ;
        }
    }


    /**
     * Nastavení použití Hotswap agenta
     *
     * @param configuration     aktuální konfigurace
     */
    private void patchConfiguration( ApplicationConfiguration configuration, String jre )
    {
        configuration.setVMParameters( patchVmParameters( configuration.getVMParameters(), jre ) ) ;
    }

    /**
     * Nastavení použití Hotswap agenta
     *
     * @param configuration     aktuální konfigurace
     */
    private void patchConfiguration( MavenRunConfiguration configuration, String jre )
    {
        MavenRunnerSettings settings  = MavenRunner.getInstance(configuration.getProject()).getSettings() ;

        if ( settings != null )
        {
            settings.setVmOptions( patchVmParameters( settings.getVmOptions(), jre ) ) ;
        }

        if ( configuration.getRunnerSettings() == null )
        {
            configuration.setRunnerSettings(new MavenRunnerSettings());
        }

        configuration.getRunnerSettings().setVmOptions( patchVmParameters( configuration.getRunnerSettings().getVmOptions(), jre ) ) ;
    }

    /**
     * Spuštění stahování
     *
     * @param project       aktuální projekt
     * @param tagName       aktuální release
     * @param task          akce, která se spustí při stahování
     */
    private void execute( @NotNull Project project, @NotNull String tagName, @NotNull Consumer<HttpURLConnection> task)
    {
        HttpURLConnection connection = null;
        try
        {
            connection = HttpConfigurable.getInstance().openHttpConnection( String.format( ZIP_URL_PATTERN, tagName, tagName ) );
            task.consume(connection);
        }
        catch (IOException e)
        {
            warn(project, e.getMessage()) ;
        }
        finally
        {
            if (connection != null)
            {
                connection.disconnect();
            }
        }
    }

    /**
     * Notifikace chyby
     *
     * @param project       aktuální projekt
     * @param message       zobrazováná zpráva
     */
    private void error( Project project, String message )
    {
        new Notification( getPluginDescriptor().getName(), getPluginDescriptor().getName(), message, NotificationType.ERROR ).notify( project ) ;
    }

    /**
     * Notifikace upozornění
     *
     * @param project       aktuální projekt
     * @param message       zobrazováná zpráva
     */
    private void warn(Project project, String message)
    {
        new Notification( getPluginDescriptor().getName(), getPluginDescriptor().getName(), message, NotificationType.WARNING ).notify( project ) ;
    }

    /**
     * Notifikace
     *
     * @param project       aktuální projekt
     * @param message       zobrazováná zpráva
     */
    private void info(Project project, String message)
    {
        new Notification( getPluginDescriptor().getName(), getPluginDescriptor().getName(), message, NotificationType.INFORMATION ).notify( project ) ;
    }

}
