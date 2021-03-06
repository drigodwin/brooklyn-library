/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.brooklyn.entity.database.postgresql;

import static java.lang.String.format;
import static org.apache.brooklyn.util.ssh.BashCommands.INSTALL_CURL;
import static org.apache.brooklyn.util.ssh.BashCommands.alternativesGroup;
import static org.apache.brooklyn.util.ssh.BashCommands.chainGroup;
import static org.apache.brooklyn.util.ssh.BashCommands.dontRequireTtyForSudo;
import static org.apache.brooklyn.util.ssh.BashCommands.executeCommandThenAsUserTeeOutputToFile;
import static org.apache.brooklyn.util.ssh.BashCommands.fail;
import static org.apache.brooklyn.util.ssh.BashCommands.ifExecutableElse0;
import static org.apache.brooklyn.util.ssh.BashCommands.ifExecutableElse1;
import static org.apache.brooklyn.util.ssh.BashCommands.installPackage;
import static org.apache.brooklyn.util.ssh.BashCommands.sudo;
import static org.apache.brooklyn.util.ssh.BashCommands.sudoAsUser;
import static org.apache.brooklyn.util.ssh.BashCommands.warn;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.security.InvalidParameterException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import org.apache.brooklyn.api.location.OsDetails;
import org.apache.brooklyn.core.effector.ssh.SshEffectorTasks;
import org.apache.brooklyn.core.entity.Attributes;
import org.apache.brooklyn.core.sensor.BasicAttributeSensorAndConfigKey;
import org.apache.brooklyn.entity.database.DatastoreMixins;
import org.apache.brooklyn.entity.software.base.AbstractSoftwareProcessSshDriver;
import org.apache.brooklyn.entity.software.base.SoftwareProcess;
import org.apache.brooklyn.location.ssh.SshMachineLocation;
import org.apache.brooklyn.util.collections.MutableList;
import org.apache.brooklyn.util.collections.MutableMap;
import org.apache.brooklyn.util.core.task.DynamicTasks;
import org.apache.brooklyn.util.core.task.ssh.SshTasks;
import org.apache.brooklyn.util.core.task.ssh.SshTasks.OnFailingTask;
import org.apache.brooklyn.util.core.task.system.ProcessTaskWrapper;
import org.apache.brooklyn.util.exceptions.Exceptions;
import org.apache.brooklyn.util.net.Urls;
import org.apache.brooklyn.util.os.Os;
import org.apache.brooklyn.util.stream.Streams;
import org.apache.brooklyn.util.text.Identifiers;
import org.apache.brooklyn.util.text.StringEscapes;
import org.apache.brooklyn.util.text.StringFunctions;
import org.apache.brooklyn.util.text.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.io.Files;

/**
 * The SSH implementation of the {@link PostgreSqlDriver}.
 */
public class PostgreSqlSshDriver extends AbstractSoftwareProcessSshDriver implements PostgreSqlDriver {

    public static final Logger log = LoggerFactory.getLogger(PostgreSqlSshDriver.class);

    public PostgreSqlSshDriver(PostgreSqlNodeImpl entity, SshMachineLocation machine) {
        super(entity, machine);

        entity.sensors().set(Attributes.LOG_FILE_LOCATION, getLogFile());
    }

    /*
     * TODO this is much messier than we would like because postgres runs as user postgres,
     * meaning the dirs must be RW by that user, and accessible (thus all parent paths),
     * which may rule out putting it in a location used by the default user.
     * Two irritating things:
     * * currently we sometimes make up a different onbox base dir;
     * * currently we put files to /tmp for staging
     * Could investigate if it really needs to run as user postgres;
     * could also see whether default user can be added to group postgres,
     * and the run dir (and all parents) made accessible to group postgres.
     */
    @Override
    public void install() {
        String version = getEntity().getConfig(SoftwareProcess.SUGGESTED_VERSION);
        String majorMinorVersion = version.substring(0, version.lastIndexOf("-"));
        String shortVersion = majorMinorVersion.replace(".", "");

        String altTarget = "/opt/brooklyn/postgres/";
        String altInstallDir = Urls.mergePaths(altTarget, "install/"+majorMinorVersion);
        
        Iterable<String> pgctlLocations = ImmutableList.of(
            altInstallDir+"/bin",
            "/usr/lib/postgresql/"+majorMinorVersion+"/bin/",
            "/opt/local/lib/postgresql"+shortVersion+"/bin/",
            "/usr/pgsql-"+majorMinorVersion+"/bin",
            "/usr/local/bin/",
            "/usr/bin/",
            "/bin/");

        DynamicTasks.queueIfPossible(SshTasks.dontRequireTtyForSudo(getMachine(),
            // sudo is absolutely required here, in customize we set user to postgres
            OnFailingTask.FAIL)).orSubmitAndBlock();
        DynamicTasks.waitForLast();

        // Check whether we can find a usable pg_ctl, and if not install one
        MutableList<String> findOrInstall = MutableList.<String>of()
            .append("which pg_ctl")
            .appendAll(Iterables.transform(pgctlLocations, StringFunctions.formatter("test -x %s/pg_ctl")))
            .append(installPackage(ImmutableMap.of(
                "yum", "postgresql"+shortVersion+" postgresql"+shortVersion+"-server",
                "apt", "postgresql-"+majorMinorVersion,
                "port", "postgresql"+shortVersion+" postgresql"+shortVersion+"-server"
                ), null))
                // due to impl of installPackage, it will not come to the line below I don't think
                .append(warn(format("WARNING: failed to find or install postgresql %s binaries", majorMinorVersion)));

        // Link to correct binaries folder (different versions of pg_ctl and psql don't always play well together)
        MutableList<String> linkFromHere = MutableList.<String>of()
            .append(ifExecutableElse1("pg_ctl", chainGroup(
                "PG_EXECUTABLE=`which pg_ctl`",
                "PG_DIR=`dirname $PG_EXECUTABLE`",
                "echo 'found pg_ctl in '$PG_DIR' on path so linking PG bin/ to that dir'",
                "ln -s $PG_DIR bin")))
                .appendAll(Iterables.transform(pgctlLocations, givenDirIfFileExistsInItLinkToDir("pg_ctl", "bin")))
                .append(fail(format("WARNING: failed to find postgresql %s binaries for pg_ctl, may already have another version installed; aborting", majorMinorVersion), 9));

        newScript(INSTALLING)
        .body.append(
            dontRequireTtyForSudo(),
            ifExecutableElse0("yum", getYumRepository(version, majorMinorVersion, shortVersion)),
            ifExecutableElse0("apt-get", getAptRepository()),
            "rm -f bin", // if left over from previous incomplete/failed install (not sure why that keeps happening!)
            alternativesGroup(findOrInstall),
            alternativesGroup(linkFromHere))
            .failOnNonZeroResultCode()
            .queue();
        
        // check that the proposed install dir is one that user postgres can access
        // TODO if command above fails then the following `getUnchecked` blocks forever
        if (DynamicTasks.queue(SshEffectorTasks.ssh(sudoAsUser("postgres", "ls "+getInstallDir())).allowingNonZeroExitCode()
                .summary("check postgres user can access install dir")).asTask().getUnchecked()!=0) {
            log.info("Postgres install dir "+getInstallDir()+" for "+getEntity()+" is not accessible to user 'postgres'; " + "using "+altInstallDir+" instead");
            String newRunDir = Urls.mergePaths(altTarget, "apps", getEntity().getApplication().getId(), getEntity().getId());
            if (DynamicTasks.queue(SshEffectorTasks.ssh("ls "+altInstallDir+"/pg_ctl").allowingNonZeroExitCode()
                    .summary("check whether "+altInstallDir+" is set up")).asTask().getUnchecked()==0) {
                // alt target already exists with binary; nothing to do for install
            } else {
                DynamicTasks.queue(SshEffectorTasks.ssh(
                    "mkdir -p "+altInstallDir,
                    "rm -rf '"+altInstallDir+"'",
                    "mv "+getInstallDir()+" "+altInstallDir,
                    "rm -rf '"+getInstallDir()+"'",
                    "ln -s "+altInstallDir+" "+getInstallDir(),
                    "mkdir -p " + newRunDir,
                    "chown -R postgres:postgres "+altTarget).runAsRoot().requiringExitCodeZero()
                    .summary("move install dir from user to postgres owned space"));
            }
            DynamicTasks.waitForLast();
            setInstallDir(altInstallDir);
            setRunDir(newRunDir);
        }
    }

    private String getYumRepository(String version, String majorMinorVersion, String shortVersion) {
        // postgres becomes available if you add the repos using an RPM such as
        // http://yum.postgresql.org/9.3/redhat/rhel-6-i386/pgdg-centos93-9.3-1.noarch.rpm
        // fedora, rhel, sl, and centos supported for RPM's

        OsDetails osDetails = getMachine().getMachineDetails().getOsDetails();
        String arch = osDetails.getArch();
        String osMajorVersion = osDetails.getVersion();
        String osName = osDetails.getName();

        log.debug("postgres detecting yum information for "+getEntity()+" at "+getMachine()+": "+osName+", "+osMajorVersion+", "+arch);

        if (osName==null) osName = ""; else osName = osName.toLowerCase();

        if (osName.equals("ubuntu")) return "echo skipping yum repo setup as this is not an rpm environment";

        if (osName.equals("rhel") || osName.contains("red hat")) osName = "redhat";
        else if (osName.equals("centos")) osName = "centos";
        else if (osName.equals("sl") || osName.startsWith("scientific")) osName = "sl";
        else if (osName.equals("fedora")) osName = "fedora";
        else {
            log.debug("insufficient OS family information '"+osName+"' for "+getMachine()+" when installing "+getEntity()+" (yum repos); treating as centos");
            osName = "centos";
        }

        if (Strings.isBlank(arch)) {
            log.warn("Insuffient architecture information '"+arch+"' for "+getMachine()+"when installing "+getEntity()+"; treating as x86_64");
            arch = "x86_64";
        }

        if (Strings.isBlank(osMajorVersion)) {
            if (osName.equals("fedora")) osMajorVersion = "20";
            else osMajorVersion = "6";
            log.warn("Insuffient OS version information '"+getMachine().getOsDetails().getVersion()+"' for "+getMachine()+"when installing "+getEntity()+" (yum repos); treating as "+osMajorVersion);
        } else {
            if (osMajorVersion.indexOf(".")>0) 
                osMajorVersion = osMajorVersion.substring(0, osMajorVersion.indexOf('.'));
        }

        return chainGroup(
                INSTALL_CURL,
                sudo(format("curl http://yum.postgresql.org/%s/redhat/rhel-%s-%s/pgdg-%s%s-%s.noarch.rpm -o pgdg.rpm", majorMinorVersion, osMajorVersion, arch, osName, shortVersion, version)),
                sudo("rpm -Uvh pgdg.rpm")
            );
    }

    private String getAptRepository() {
        return chainGroup(
                INSTALL_CURL,
                "curl http://apt.postgresql.org/pub/repos/apt/ACCC4CF8.asc | sudo tee -a apt-key add",
                "echo \"deb http://apt.postgresql.org/pub/repos/apt/ $(sudo lsb_release --codename --short)-pgdg main\" | sudo tee -a /etc/apt/sources.list.d/postgresql.list"
            );
    }

    private static Function<String, String> givenDirIfFileExistsInItLinkToDir(final String filename, final String linkToMake) {
        return new Function<String, String>() {
            public String apply(@Nullable String dir) {
                return ifExecutableElse1(Urls.mergePaths(dir, filename),
                    chainGroup("echo 'found "+filename+" in "+dir+" so linking to it in "+linkToMake+"'", "ln -s "+dir+" "+linkToMake));
            }
        };
    }

    @Override
    public void customize() {
        // Some OSes start postgres during package installation
        DynamicTasks.queue(SshEffectorTasks.ssh(sudoAsUser("postgres", "/etc/init.d/postgresql stop")).allowingNonZeroExitCode()).get();

        newScript(CUSTOMIZING)
        .body.append(
            sudo("mkdir -p " + getDataDir()),
            sudo("chown postgres:postgres " + getDataDir()),
            sudo("chmod 700 " + getDataDir()),
            sudo("touch " + getLogFile()),
            sudo("chown postgres:postgres " + getLogFile()),
            sudo("touch " + getPidFile()),
            sudo("chown postgres:postgres " + getPidFile()),
            alternativesGroup(
                chainGroup(format("test -e %s", getInstallDir() + "/bin/initdb"),
                    sudoAsUser("postgres", getInstallDir() + "/bin/initdb -D " + getDataDir())),
                    callPgctl("initdb", true)))
                    .failOnNonZeroResultCode()
                    .execute();

        String configUrl = getEntity().getConfig(PostgreSqlNode.CONFIGURATION_FILE_URL);
        if (Strings.isBlank(configUrl)) {
            // http://wiki.postgresql.org/wiki/Tuning_Your_PostgreSQL_Server
            // If the same setting is listed multiple times, the last one wins.
            DynamicTasks.queue(SshEffectorTasks.ssh(
                executeCommandThenAsUserTeeOutputToFile(
                    chainGroup(
                        "echo \"listen_addresses = '*'\"",
                        "echo \"port = " + getEntity().getPostgreSqlPort() +  "\"",
                        "echo \"max_connections = " + getEntity().getMaxConnections() +  "\"",
                        "echo \"shared_buffers = " + getEntity().getSharedMemory() +  "\"",
                        "echo \"external_pid_file = '" + getPidFile() +  "'\""),
                        "postgres", getDataDir() + "/postgresql.conf")));
        } else {
            String contents = processTemplate(configUrl);
            DynamicTasks.queue(
                SshEffectorTasks.put("/tmp/postgresql.conf").contents(contents),
                SshEffectorTasks.ssh(sudoAsUser("postgres", "cp /tmp/postgresql.conf " + getDataDir() + "/postgresql.conf")));
        }

        String authConfigUrl = getEntity().getConfig(PostgreSqlNode.AUTHENTICATION_CONFIGURATION_FILE_URL);
        if (Strings.isBlank(authConfigUrl)) {
            DynamicTasks.queue(SshEffectorTasks.ssh(
                // TODO give users control which hosts can connect and the authentication mechanism
                executeCommandThenAsUserTeeOutputToFile("echo \"host all all 0.0.0.0/0 md5\"", "postgres", getDataDir() + "/pg_hba.conf")));
        } else {
            String contents = processTemplate(authConfigUrl);
            DynamicTasks.queue(
                SshEffectorTasks.put("/tmp/pg_hba.conf").contents(contents),
                SshEffectorTasks.ssh(sudoAsUser("postgres", "cp /tmp/pg_hba.conf " + getDataDir() + "/pg_hba.conf")));
        }

        // Wait for commands to complete before running the creation script
        DynamicTasks.waitForLast();
        if(Boolean.TRUE.equals(entity.getConfig(PostgreSqlNode.INITIALIZE_DB))){
            initializeNewDatabase();
        }
        // Capture log file contents if there is an error configuring the database
        try {
            executeDatabaseCreationScript();
        } catch (RuntimeException r) {
            logTailOfPostgresLog();
            throw Exceptions.propagate(r);
        }

        // Try establishing an external connection. If you get a "Connection refused...accepting TCP/IP connections
        // on port 5432?" error then the port is probably closed. Check that the firewall allows external TCP/IP
        // connections (netstat -nap). You can open a port with lokkit or by configuring the iptables.
    }

    private void initializeNewDatabase() {
        String createUserCommand = String.format(
                "\"CREATE USER %s WITH PASSWORD '%s'; \"",
                StringEscapes.escapeSql(getUsername()), 
                StringEscapes.escapeSql(getUserPassword())
        );
        String createDatabaseCommand = String.format(
                "\"CREATE DATABASE %s OWNER %s\"",
                StringEscapes.escapeSql(getDatabaseName()),
                StringEscapes.escapeSql(getUsername()));

        String createRolesAdditionalCommand = "";

        if (entity.getConfig(PostgreSqlNode.ROLES) != null && !entity.getConfig(PostgreSqlNode.ROLES).isEmpty()) {
            String createRolesQuery = buildCreateRolesQuery();
            createRolesAdditionalCommand =
                    sudoAsUser("postgres", getInstallDir() + "/bin/psql -p " + entity.getAttribute(PostgreSqlNode.POSTGRESQL_PORT) +
                            " --command="+ createRolesQuery);
        }

        newScript("initializing user and database")
        .body.append(
                "cd " + getInstallDir(),
                callPgctl("start", true),
                sudoAsUser("postgres", getInstallDir() + "/bin/psql -p " + entity.getAttribute(PostgreSqlNode.POSTGRESQL_PORT) + 
                        " --command="+ createUserCommand),
                sudoAsUser("postgres", getInstallDir() + "/bin/psql -p " + entity.getAttribute(PostgreSqlNode.POSTGRESQL_PORT) + 
                                " --command="+ createDatabaseCommand),
                createRolesAdditionalCommand,
                callPgctl("stop", true))
                .failOnNonZeroResultCode().execute();
    }

    @VisibleForTesting
    protected String buildCreateRolesQuery() {
        Map<String, Map<String, ?>> roles = entity.getConfig(PostgreSqlNode.ROLES);
        StringBuilder builder = new StringBuilder("\"");

        for (Map.Entry<String, ? extends Map<String, ?>> entry : roles.entrySet()) {
            String roleName = entry.getKey();
            Map<String, ?> roleConfig = entry.getValue();
            if (roleConfig == null) roleConfig = ImmutableMap.of();
            
            if (Strings.isBlank(roleName)) {
                throw new NullPointerException("Role name must not be blank, but got "+roles);
            }
            validateInput(roleName, "role name '"+roleName+"'");
                
            builder.append(String.format("CREATE ROLE %s", roleName));
            
            
            if (roleConfig != null && roleConfig.containsKey(PostgreSqlNode.ROLE_PROPERTIES_KEY)) {
                Object rawProps = roleConfig.get("properties");
                String props = validateInput((String) rawProps, "role '"+roleName+"' property "+rawProps);;
                builder.append(String.format(" WITH %s; ", props));
            } else {
                builder.append("; ");
            }

            if (roleConfig.containsKey(PostgreSqlNode.ROLE_PRIVILEGES_KEY)) {
                List<String> privileges = toListOfStrings(roleConfig.get(PostgreSqlNode.ROLE_PRIVILEGES_KEY));
                for (Object rawPrivilege : privileges) {
                    String privilege = validateInput((String) rawPrivilege, "role '"+roleName+"' privilege "+rawPrivilege);
                    builder.append(String.format("GRANT %s TO %s; ", privilege, roleName));
                }
            }
            
            Set<String> otherConfig = Sets.difference(roleConfig.keySet(), 
                    ImmutableSet.of(PostgreSqlNode.ROLE_PROPERTIES_KEY, PostgreSqlNode.ROLE_PRIVILEGES_KEY));
            if (!otherConfig.isEmpty()) {
                throw new IllegalArgumentException("Invalid configuration for role "+roleName+", got "+roles);
            }
        }

        builder.append("\"");

        return builder.toString();
    }

    private String validateInput(String input, String errContext) {
        String regex = "[A-Za-z_,\\s]+";
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(input);

        if (Strings.isBlank(input)) {
            throw new NullPointerException("Must be non-blank for "+errContext);
        }
        if (matcher.find() && matcher.start() == 0 && matcher.end() == input.length()) {
            return input;
        }

        throw new InvalidParameterException("Query input seems to be insecure. Make sure you pass a valid value for "+errContext);
    }
    
    // TODO Duplicate of JcloudsLocation.toListOfStrings
    private static List<String> toListOfStrings(Object v) {
        List<String> result = Lists.newArrayList();
        if (v instanceof Iterable) {
            for (Object o : (Iterable<?>)v) {
                result.add(o.toString());
            }
        } else if (v instanceof Object[]) {
            for (int i = 0; i < ((Object[])v).length; i++) {
                result.add(((Object[])v)[i].toString());
            }
        } else if (v instanceof String) {
            result.add((String) v);
        } else {
            throw new IllegalArgumentException("Invalid type for List<String>: "+v+" of type "+v.getClass());
        }
        return result;
    }

    private String getConfigOrDefault(BasicAttributeSensorAndConfigKey<String> key, String def) {
        String val = entity.getConfig(key);
        if (Strings.isEmpty(val)) {
            val = entity.sensors().get(key);
            if (Strings.isEmpty(val)) {
                val = def;
                log.debug(entity + " has no config specified for " + key + "; using default `" + def + "`");
                entity.sensors().set(key, val);
            }
        }
        return val;
    }
    
    protected String getDatabaseName() {
        return getConfigOrDefault(PostgreSqlNode.DATABASE, PostgreSqlNode.DEFAULT_DB_NAME);
    }
    
    protected String getUsername(){
        return getConfigOrDefault(PostgreSqlNode.USERNAME, PostgreSqlNode.DEFAULT_USERNAME);
    }
    
    protected String getUserPassword() {
        return getConfigOrDefault(PostgreSqlNode.PASSWORD, Strings.makeRandomId(8));
    }

    protected void executeDatabaseCreationScript() {
        if (copyDatabaseCreationScript()) {
            newScript("running postgres creation script")
            .body.append(
                "cd " + getInstallDir(),
                callPgctl("start", true),
                sudoAsUser("postgres", getInstallDir() + "/bin/psql -p " + entity.getAttribute(PostgreSqlNode.POSTGRESQL_PORT) + " --file " + getRunDir() + "/creation-script.sql"),
                callPgctl("stop", true))
                .failOnNonZeroResultCode()
                .execute();
        }
    }

    private boolean installFile(InputStream contents, String destName) {
        String uid = Identifiers.makeRandomId(8);
        // TODO currently put in /tmp for staging, since run dir may not be accessible to ssh user
        getMachine().copyTo(contents, "/tmp/"+destName+"_"+uid);
        DynamicTasks.queueIfPossible(SshEffectorTasks.ssh(
            "cd "+getRunDir(), 
            "mv /tmp/"+destName+"_"+uid+" "+destName,
            "chown postgres:postgres "+destName,
            "chmod 644 "+destName)
            .runAsRoot().requiringExitCodeZero())
            .orSubmitAndBlock(getEntity()).andWaitForSuccess();
        return true;
    }
    private boolean copyDatabaseCreationScript() {
        InputStream creationScript = DatastoreMixins.getDatabaseCreationScript(entity);
        if (creationScript==null)
            return false;
        return installFile(creationScript, "creation-script.sql");
    }

    public String getDataDir() {
        return getRunDir() + "/data";
    }

    public String getLogFile() {
        return getRunDir() + "/postgresql.log";
    }

    public String getPidFile() {
        return getRunDir() + "/postgresql.pid";
    }

    /** @deprecated since 0.7.0 renamed {@link #logTailOfPostgresLog()} */
    @Deprecated
    public void copyLogFileContents() { logTailOfPostgresLog(); }
    public void logTailOfPostgresLog() {
        try {
            File file = Os.newTempFile("postgresql-"+getEntity().getId(), "log");
            int result = getMachine().copyFrom(getLogFile(), file.getAbsolutePath());
            if (result != 0) throw new IllegalStateException("Could not access log file " + getLogFile());
            log.info("Saving {} contents as {}", getLogFile(), file);
            Streams.logStreamTail(log, "postgresql.log", Streams.byteArrayOfString(Files.toString(file, Charsets.UTF_8)), 1024);
            file.delete();
        } catch (IOException ioe) {
            log.debug("Error reading copied log file: {}", ioe);
        }
    }

    protected String callPgctl(String command, boolean waitForIt) {
        return sudoAsUser("postgres", getInstallDir() + "/bin/pg_ctl -D " + getDataDir() +
            " -l " + getLogFile() + (waitForIt ? " -w " : " ") + command);
    }

    @Override
    public void launch() {
        log.info(String.format("Starting entity %s at %s", this, getLocation()));
        newScript(MutableMap.of("usePidFile", false), LAUNCHING)
        .body.append(callPgctl("start", false))
        .execute();
    }

    @Override
    public boolean isRunning() {
        return newScript(MutableMap.of("usePidFile", getPidFile()), CHECK_RUNNING)
            .body.append(getStatusCmd())
            .execute() == 0;
    }

    @Override
    public void stop() {
        newScript(MutableMap.of("usePidFile", false), STOPPING)
        .body.append(callPgctl((entity.getConfig(PostgreSqlNode.DISCONNECT_ON_STOP) ? "-m immediate " : "") + "stop", false))
        .failOnNonZeroResultCode()
        .execute();
        newScript(MutableMap.of("usePidFile", getPidFile(), "processOwner", "postgres"), STOPPING).execute();
    }

    @Override
    public PostgreSqlNodeImpl getEntity() {
        return (PostgreSqlNodeImpl) super.getEntity();
    }

    @Override
    public String getStatusCmd() {
        return callPgctl("status", false);
    }

    public ProcessTaskWrapper<Integer> executeScriptAsync(String commands) {
        String filename = "postgresql-commands-"+Identifiers.makeRandomId(8);
        installFile(Streams.newInputStreamWithContents(commands), filename);
        return executeScriptFromInstalledFileAsync(filename);
    }

    public ProcessTaskWrapper<Integer> executeScriptFromInstalledFileAsync(String filenameAlreadyInstalledAtServer) {
        return DynamicTasks.queue(
            SshEffectorTasks.ssh(
                "cd "+getRunDir(),
                sudoAsUser("postgres", getInstallDir() + "/bin/psql -p " + entity.getAttribute(PostgreSqlNode.POSTGRESQL_PORT) + " --file " + filenameAlreadyInstalledAtServer))
                .summary("executing datastore script "+filenameAlreadyInstalledAtServer));
    }

}
