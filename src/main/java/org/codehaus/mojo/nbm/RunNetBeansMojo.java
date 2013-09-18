/* ==========================================================================
 * Copyright 2003-2007 Mevenide Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 * =========================================================================
 */
package org.codehaus.mojo.nbm;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.codehaus.plexus.util.IOUtil;
import org.codehaus.plexus.util.Os;
import org.codehaus.plexus.util.cli.CommandLineUtils;
import org.codehaus.plexus.util.cli.Commandline;
import org.codehaus.plexus.util.cli.StreamConsumer;

/**
 * Run NetBeans IDE with additional custom module clusters, 
 * to be used in conjunction with nbm:cluster.
 * Semi-deprecated; used only for standalone modules and "suites".
 * @author <a href="mailto:mkleint@codehaus.org">Milos Kleint</a>
 *
 */
@Mojo(name="run-ide", aggregator=true, requiresDependencyResolution= ResolutionScope.RUNTIME )
public class RunNetBeansMojo
        extends AbstractMojo
{

    /**
     * directory where the module(s)' NetBeans cluster(s) are located.
     * is related to nbm:cluster goal.
     */
    @Parameter(required=true, defaultValue="${project.build.directory}/netbeans_clusters")
    protected File clusterBuildDir;
    /**
     * directory where the the NetBeans platform/IDE installation is,
     * denotes the root directory of NetBeans installation.
     */
    @Parameter(required=true, property="netbeans.installation")
    protected File netbeansInstallation;
    /**
     * NetBeans user directory for the executed instance.
     */
    @Parameter(required=true, defaultValue="${project.build.directory}/userdir", property="netbeans.userdir")
    protected File netbeansUserdir;
    /**
     * additional command line arguments. 
     */
    @Parameter(property="netbeans.run.params")
    protected String additionalArguments;
    
    /**
     * Attach a debugger to the application JVM. If set to "true", the process will suspend and wait for a debugger to attach
     * on port 5005. If set to some other string, that string will be appended to the <code>additionalArguments</code>, allowing you to configure
     * arbitrary debug-ability options (without overwriting the other options specified through the <code>additionalArguments</code>
     * parameter).
     * @since 3.11.1
     */
    @Parameter(property="netbeans.run.params.debug")
    protected String debugAdditionalArguments;    

    /**
     * 
     * @throws org.apache.maven.plugin.MojoExecutionException 
     * @throws org.apache.maven.plugin.MojoFailureException 
     */
    public void execute()
        throws MojoExecutionException, MojoFailureException
    {
        netbeansUserdir.mkdirs();

        List<File> clusters = new ArrayList<File>();
        if ( !clusterBuildDir.exists() || clusterBuildDir.listFiles() == null )
        {
            throw new MojoExecutionException(
                                              "No clusters to include in execution found. Please run the nbm:cluster or nbm:cluster-app goals before this one." );
        }
        File[] fls = clusterBuildDir.listFiles();
        for ( int i = 0; i < fls.length; i++ )
        {
            if ( fls[i].isDirectory() )
            {
                clusters.add( fls[i] );
            }
        }
        StringBuilder buff = new StringBuilder();
        for ( File cluster : clusters )
        {
            buff.append( cluster.getAbsolutePath() );
            buff.append( ":" );
        }
        if ( buff.lastIndexOf( ":" ) > -1 )
        {
            buff.deleteCharAt( buff.lastIndexOf( ":" ) );
        }
        //http://www.netbeans.org/issues/show_bug.cgi?id=174819
        StringReader sr =
            new StringReader( "netbeans_extraclusters=\"" + buff.toString() + "\"\n" + "extraclusters=\""
                + buff.toString() + "\"\n" + "extra_clusters=\"" + buff.toString() + "\"" );

        //now check what the exec names are to figure the right XXX.clusters name
        File binDir = new File( netbeansInstallation, "bin" );
        File[] execs = binDir.listFiles();
        String clust = null;
        if ( execs != null )
        {
            for ( File f : execs )
            {
                String name = f.getName();
                if ( name.contains( "_w.exe" ) )
                {
                    continue;
                }
                name = name.replaceFirst( "(64)?([.]exe)?$", "" );
                if ( !name.contains( "." ) )
                {
                    if ( clust == null )
                    {
                        clust = name;
                    }
                    else
                    {
                        if ( !clust.equals( name ) )
                        {
                            getLog().debug( "When examining executable names, found clashing results " + f.getName()
                                                + " " + clust );
                        }
                    }
                }
            }
        }
        if ( clust == null )
        {
            clust = "netbeans";
        }

        // write XXX.conf file with cluster information...
        File etc = new File( netbeansUserdir, "etc" );
        etc.mkdirs();
        File confFile = new File( etc, clust + ".conf" );
        FileOutputStream conf = null;
        try
        {
            conf = new FileOutputStream( confFile );
            IOUtil.copy( sr, conf );
        }
        catch ( IOException ex )
        {
            throw new MojoExecutionException( "Error writing " + confFile, ex );
        }
        finally
        {
            IOUtil.close( conf );
        }

        boolean windows = Os.isFamily( "windows" );
        Commandline cmdLine = new Commandline();
        File exec;
        if ( windows )
        {
            exec = new File( netbeansInstallation, "bin\\nb.exe" );
            if ( !exec.exists() )
            {
                // in 6.7 and onward, there's no nb.exe file.
                exec = new File( netbeansInstallation, "bin\\" + clust + ".exe" );
                String jdkHome = System.getenv( "JAVA_HOME" );
                if ( jdkHome != null )
                {
                    if ( new File( jdkHome, "jre\\lib\\amd64\\jvm.cfg" ).exists() )
                    {
                        File exec64 = new File( netbeansInstallation, "bin\\" + clust + "64.exe" );
                        if ( exec64.isFile() )
                        {
                            exec = exec64;
                        }
                    }
                }
                cmdLine.addArguments( new String[] { "--console", "suppress" } );
            }
        }
        else
        {
            exec = new File( netbeansInstallation, "bin/" + clust );
        }
        cmdLine.setExecutable( exec.getAbsolutePath() );

        try
        {
            String[] args = new String[]
            {
                //TODO --jdkhome
                "--userdir",
                netbeansUserdir.getAbsolutePath(),
                "-J-Dnetbeans.logger.console=true",
                "-J-ea",
            };
            cmdLine.addArguments( args );
            getLog().info( "Additional arguments=" + additionalArguments );
            cmdLine.addArguments( CommandLineUtils.translateCommandline( additionalArguments ) );
            cmdLine.addArguments( CommandLineUtils.translateCommandline( getDebugAdditionalArguments() ) );
            for ( int i = 0; i < cmdLine.getArguments().length; i++ )
            {
                getLog().info( "      " + cmdLine.getArguments()[i] );
            }
            getLog().info( "Executing: " + cmdLine.toString() );
            StreamConsumer out = new StreamConsumer()
            {

                public void consumeLine( String line )
                {
                    getLog().info( line );
                }
            };
            CommandLineUtils.executeCommandLine( cmdLine, out, out );

        }
        catch ( Exception e )
        {
            throw new MojoExecutionException( "Failed executing NetBeans", e );
        }
    }
    
    private String getDebugAdditionalArguments()
    {
       if ( "true".equals( debugAdditionalArguments ) )
        {
            return "-Xdebug -Xnoagent -Djava.compiler=NONE -Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=5005";
        }
        return debugAdditionalArguments;
    }    
}
