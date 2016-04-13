/*
 * Copyright 2016 Fizzed, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.fizzed.stork.deploy;

import com.fizzed.blaze.Contexts;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Deployer {
    private static final Logger log = LoggerFactory.getLogger(Deployer.class);

    public Deployer() {
        // do nothing
    }
    
    public void verify(Assembly assembly, DeployOptions options, String uri) throws IOException, DeployerException {
        try (Target target = Targets.connect(uri)) {
            deploy(assembly, options, target, false);
        }
    }
    
    public void verify(Assembly assembly, DeployOptions options, Target target) throws IOException, DeployerException {
        deploy(assembly, options, target, false);
    }
    
    public void deploy(Assembly assembly, DeployOptions options, String uri) throws IOException, DeployerException {
        try (Target target = Targets.connect(uri)) {
            deploy(assembly, options, target, true);
        }
    }
    
    public void deploy(Assembly assembly, DeployOptions options, Target target) throws IOException, DeployerException {
        deploy(assembly, options, target, true);
    }
    
    private void deploy(Assembly assembly, DeployOptions options, Target target, boolean includeDeploy) throws IOException, DeployerException {
        // use assembly + target to prepare for what to install
        Deployment install = Deployments.install(assembly, target, options);

        // existing deployment
        ExistingDeployment existing = Deployments.existing(install, target);

        log.info("");
        logAssembly(assembly);
        log.info("");
        logTarget(target);
        log.info("");
        logExistingDeployment(existing);
        log.info("");
        logInstallDeployment(install, existing);
        log.info("");
        
        /**
        if (!options.getYes()) {
            String answer = Contexts.prompt("Do you want to continue?");
        }
        */
        
        //
        // verify install would succeed as much as we can
        //

        assembly.verify();

        // if we have daemons do we have support for the target platform?
        if (assembly.hasDaemons()) {
            if (!assembly.hasDaemons(target.getInitType())) {
                throw new DeployerException("Assembly has daemons but none matching target init type " + target.getInitType());
            }
        }

        // verify target user exists
        if (install.getUser().isPresent() && !target.hasUser(install.getUser().get())) {
            throw new DeployerException(
                "User '" + install.getUser().get() + "' does not exist on target. "
                + "Run something like 'sudo useradd -r " + install.getUser().get() + "' then re-run.");
        }

        // verify target group exists
        if (install.getGroup().isPresent() && !target.hasGroup(install.getGroup().get())) {
            throw new DeployerException(
                "Group '" + install.getGroup().get() + "' does not exist on target. " 
                + "Run something like 'sudo groupadd -r " + install.getGroup().get() + "' then re-run.");
        }

        // TODO: verify commands we need exist
        //   tar if (.tar.gz), gunzip if (.zip)

        if (!includeDeploy) {
            return;
        }
        
        //
        // do deploy
        //

        // create version directory (where we will install to)
        target.createDirectories(true, install.getVersionDir());

        // for either fresh or upgrade installs, we need a work dir
        String targetWorkDir = target.getTempDir() + "/stork-deploy";

        // create clean work directory
        target.remove(false, targetWorkDir);
        target.createDirectories(false, targetWorkDir);

        
        
        String targetArchiveFile = targetWorkDir + "/" + assembly.getArchiveFile().getFileName();

        // upload assembly
        target.put(assembly.getArchiveFile(), targetArchiveFile);

        // unpack archive
        target.unpack(targetArchiveFile, targetWorkDir);

        if (existing.isFresh()) {
            // copy all known files to versioned dir
            Files.list(assembly.getUnpackedDir()).forEach((file) -> {
                target.copyFiles(true,
                    targetWorkDir + "/" + assembly.getUnpackedDir().getFileName() + "/" + file.getFileName(),
                    install.getVersionDir() + "/");
            });
        } else {
            // stop daemons
            if (assembly.hasDaemons()) {
                for (Daemon daemon : assembly.getDaemons(target.getInitType())) {
                    target.stopDaemon(daemon);
                }
            }

            // copy dirs overwritten on upgrades
            // from uploaded assembly -> versioned dir
            Arrays.asList("bin", "lib", "share").stream().forEach((dir) -> {
                target.copyFiles(true,
                    targetWorkDir + "/" + assembly.getUnpackedDir().getFileName() + "/" + dir,
                    install.getVersionDir() + "/");
            });

            // move dirs retained on upgrades
            // from current dir -> versioned dir
            Arrays.asList("conf", "log", "data", "run").stream().forEach((dir) -> {
                target.moveFiles(true,
                    install.getCurrentDir() + "/" + dir,
                    install.getVersionDir() + "/");
            });
        }

        
        //
        // strict ownership & permissions
        //

        Optional<String> owner = install.getOwner();
        if (owner.isPresent()) {
            target.chown(true, false, owner.get(), install.getBaseDir());
            target.chown(true, true, owner.get(), install.getVersionDir());
        }

        // NOTE: why 774?
        // only user & group can get into the directory, while anyone will at
        // least be able to know there is a directory with that name
        if (assembly.hasDirectory("bin")) {
            target.chmod(true, true, "774", install.getVersionDir() + "/bin");
        }
        
        if (assembly.hasDirectory("conf")) {
            target.chmod(true, true, "774", install.getVersionDir() + "/conf");
        }

        if (assembly.hasDirectory("lib")) {
            target.chmod(true, true, "774", install.getVersionDir() + "/lib");
        }
        
        if (assembly.hasDirectory("share/init.d")) {
            target.chmod(true, true, "774", install.getVersionDir() + "/share/init.d");
        }

        //
        // create symlink from current dir -> version dir
        //
        target.symlink(true, install.getVersionDir(), install.getCurrentDir());

        if (assembly.hasDaemons()) {
            //
            // install daemons on both fresh/upgrade
            //
            for (Daemon daemon : assembly.getDaemons(target.getInitType())) {
                target.installDaemon(install, daemon, true);
            }
            
            //
            // start daemons (ask user though on fresh installs)
            //
            boolean tryDaemonStart = true;
            
            // guard against new installs w/ conf directorys and failing to start
            // simply due to conf files requiring an edit the first time
            if (existing.isFresh()) {
                if (assembly.hasDirectory("conf")) {
                    log.warn("Your app has a 'conf' directory and this is a fresh install");
                    log.warn("Its possible you need to edit your conf files before it can start");
                    while (true) {
                        log.info("Doing prompt...");
                        String answer = Contexts.prompt("Do you want to try and start your daemons now [yes/no]? ");
                        if (answer.equalsIgnoreCase("yes")) {
                            break;
                        } else if (answer.equalsIgnoreCase("no")) {
                            tryDaemonStart = false;
                            break;
                        }
                    }
                }
            }
            
            if (tryDaemonStart) {
                for (Daemon daemon : assembly.getDaemons(target.getInitType())) {
                    target.startDaemon(daemon);
                }
            }
        }

        log.info("Deployed {} to {}", assembly, target);
    }
    
    private void logAssembly(Assembly assembly) {
        log.info("   Assembly>");
        log.info("       name: {}", assembly.getName());
        log.info("    version: v{}", (assembly.isSnapshot() ? assembly.getVersion() + " (snapshot)" : assembly.getVersion()));
        log.info("    archive: {}", assembly.getArchiveFile());
        log.info("   unpacked: {}", assembly.getUnpackedDir());
        if (assembly.hasDaemons()) {
            log.info(" init types: {}", assembly.getDaemons().keySet().stream().map((i) -> i.name()).collect(Collectors.joining(", ")));
            log.info("    daemons: {}", assembly.getDaemons().values().iterator().next().stream().map((d) -> d.getName()).collect(Collectors.joining(", ")));
        } else {
            log.info("    daemons: <none>");
        }
    }
    
    private void logTarget(Target target) {
        log.info("     Target>");
        log.info("        uri: {}", target.getUri());
        log.info("     system: {}", target.getUname());
        log.info("  init type: {}", target.getInitType());
        log.info("       impl: {}", target.getClass().getCanonicalName());
    }
    
    private void logExistingDeployment(ExistingDeployment existing) {
        log.info("   Existing>");
        
        if (existing.isFresh()) {
            log.info("   no install found");
        } else {
            log.info("   base dir: {}", existing.getBaseDir());
            log.info("current dir: {}", existing.getCurrentDir());
            log.info("version dir: {}", existing.getVersionDir());
            log.info("deployed at: {}", (existing.getDeployedAt() == null ? "<none>" : DeployHelper.toFriendlyDateTime(existing.getDeployedAt())));
            
            int i = 0;
            for (String versionDir : existing.getVersionDirs()) {
                if (i == 0) {
                    log.info("   all vers: {}",  versionDir);
                } else {
                    log.info("             {}",  versionDir);
                }
                i++;
            }
        }
    }
    
    private void logInstallDeployment(Deployment install, ExistingDeployment existing) {
        log.info("    Install>");
        log.info("       type: {}", (existing.isFresh() ? "fresh" : "upgrade"));
        log.info("   base dir: {}", install.getBaseDir());
        log.info("current dir: {}", install.getCurrentDir());
        log.info("version dir: {}", install.getVersionDir());
        log.info("    as user: {}", install.getUser().orElse("<null>"));
        log.info("   as group: {}", install.getGroup().orElse("<null>"));
    }
    
}
