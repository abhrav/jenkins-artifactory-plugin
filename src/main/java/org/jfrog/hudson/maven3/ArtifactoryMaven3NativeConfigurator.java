package org.jfrog.hudson.maven3;

import hudson.Extension;
import hudson.Launcher;
import hudson.maven.MavenModuleSet;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Action;
import hudson.model.BuildListener;
import hudson.model.Hudson;
import hudson.remoting.Which;
import hudson.tasks.BuildWrapper;
import hudson.tasks.BuildWrapperDescriptor;
import net.sf.json.JSONObject;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.jfrog.build.client.ClientProperties;
import org.jfrog.build.extractor.maven.BuildInfoRecorder;
import org.jfrog.hudson.ArtifactoryBuilder;
import org.jfrog.hudson.ArtifactoryServer;
import org.jfrog.hudson.ServerDetails;
import org.jfrog.hudson.action.ActionableHelper;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * @author Tomer Cohen
 */
public class ArtifactoryMaven3NativeConfigurator extends BuildWrapper {

    private final ServerDetails details;


    @DataBoundConstructor
    public ArtifactoryMaven3NativeConfigurator(ServerDetails details) {
        this.details = details;
    }

    public ServerDetails getDetails() {
        return details;
    }

    public String getDownloadRepositoryKey() {
        return details != null ? details.downloadRepositoryKey : null;
    }

    public String getArtifactoryName() {
        return details != null ? details.artifactoryName : null;
    }

    public String getRepositoryKey() {
        return details != null ? details.repositoryKey : null;
    }

    @Override
    public Collection<? extends Action> getProjectActions(AbstractProject project) {
        return ActionableHelper.getArtifactoryProjectAction(details.artifactoryName, project);
    }


    @Override
    public Environment setUp(AbstractBuild build, Launcher launcher, BuildListener listener)
            throws IOException, InterruptedException {
        final MavenModuleSet project = (MavenModuleSet) build.getProject();
        final File classWorldsFile = File.createTempFile("classworlds", "conf");
        return new Environment() {
            @Override
            public void buildEnvVars(Map<String, String> env) {
                super.buildEnvVars(env);
                try {
                    project.setMavenOpts(appendNewMavenOpts(project));
                } catch (IOException e) {
                    throw new RuntimeException("Unable to manipulate maven_opts for project: " + project, e);
                }
                URL resource =
                        getClass().getClassLoader().getResource("org/jfrog/hudson/maven3/classworlds-native.conf");
                File classWorldsConf = new File(resource.getFile());
                try {
                    FileUtils.copyFile(classWorldsConf, classWorldsFile);
                } catch (IOException e) {
                    throw new RuntimeException(
                            "Unable to copy classworlds file: " + classWorldsConf.getAbsolutePath() + " to: " +
                                    classWorldsFile.getAbsolutePath(), e);
                }
                env.put("classworlds.conf", classWorldsFile.getAbsolutePath());
                final ArtifactoryServer artifactoryServer = getArtifactoryServer();
                env.put(ClientProperties.PROP_CONTEXT_URL, artifactoryServer.getUrl());
                env.put(ClientProperties.PROP_RESOLVE_REPOKEY, getDownloadRepositoryKey());
            }

            @Override
            public boolean tearDown(AbstractBuild build, BuildListener listener)
                    throws IOException, InterruptedException {
                FileUtils.deleteQuietly(classWorldsFile);
                return super.tearDown(build, listener);
            }

            private String appendNewMavenOpts(MavenModuleSet project) throws IOException {
                StringBuilder mavenOpts = new StringBuilder();
                String opts = project.getMavenOpts();
                if (StringUtils.isNotBlank(opts)) {
                    mavenOpts.append(opts);
                }
                mavenOpts.append(" -Dm3plugin.lib=")
                        .append(Which.jarFile(BuildInfoRecorder.class).getAbsoluteFile().getParentFile()
                                .getAbsolutePath());
                return mavenOpts.toString();
            }

        };
    }

    public ArtifactoryServer getArtifactoryServer() {
        List<ArtifactoryServer> servers = getDescriptor().getArtifactoryServers();
        for (ArtifactoryServer server : servers) {
            if (server.getName().equals(getArtifactoryName())) {
                return server;
            }
        }
        return null;
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    @Extension(optional = true)
    public static class DescriptorImpl extends BuildWrapperDescriptor {
        public DescriptorImpl() {
            super(ArtifactoryMaven3NativeConfigurator.class);
            load();
        }

        @Override
        public boolean isApplicable(AbstractProject<?, ?> item) {
            return MavenModuleSet.class.equals(item.getClass());
        }

        @Override
        public String getDisplayName() {
            return "Resolve artifacts from Artifactory";
        }

        @Override
        public String getHelpFile() {
            return "/plugin/artifactory/ArtifactoryMaven3NativeConfigurator/help.html";
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject json) throws FormException {
            req.bindParameters(this, "maven");
            save();
            return true;
        }

        /**
         * Returns the list of {@link org.jfrog.hudson.ArtifactoryServer} configured.
         *
         * @return can be empty but never null.
         */
        public List<ArtifactoryServer> getArtifactoryServers() {
            ArtifactoryBuilder.DescriptorImpl descriptor = (ArtifactoryBuilder.DescriptorImpl)
                    Hudson.getInstance().getDescriptor(ArtifactoryBuilder.class);
            return descriptor.getArtifactoryServers();
        }
    }
}