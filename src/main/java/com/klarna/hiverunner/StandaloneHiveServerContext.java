/**
 * Copyright (C) 2013-2019 Klarna AB
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.klarna.hiverunner;

import static org.apache.hadoop.hive.conf.HiveConf.ConfVars.HADOOPBIN;
import static org.apache.hadoop.hive.conf.HiveConf.ConfVars.HIVECONVERTJOIN;
import static org.apache.hadoop.hive.conf.HiveConf.ConfVars.HIVEHISTORYFILELOC;
import static org.apache.hadoop.hive.conf.HiveConf.ConfVars.HIVEMETADATAONLYQUERIES;
import static org.apache.hadoop.hive.conf.HiveConf.ConfVars.HIVEOPTINDEXFILTER;
import static org.apache.hadoop.hive.conf.HiveConf.ConfVars.HIVESKEWJOIN;
import static org.apache.hadoop.hive.conf.HiveConf.ConfVars.HIVESTATSAUTOGATHER;
import static org.apache.hadoop.hive.conf.HiveConf.ConfVars.HIVE_CBO_ENABLED;
import static org.apache.hadoop.hive.conf.HiveConf.ConfVars.HIVE_INFER_BUCKET_SORT;
import static org.apache.hadoop.hive.conf.HiveConf.ConfVars.HIVE_SERVER2_LOGGING_OPERATION_ENABLED;
import static org.apache.hadoop.hive.conf.HiveConf.ConfVars.HIVE_SUPPORT_CONCURRENCY;
import static org.apache.hadoop.hive.conf.HiveConf.ConfVars.HIVE_WAREHOUSE_SUBDIR_INHERIT_PERMS;
import static org.apache.hadoop.hive.conf.HiveConf.ConfVars.LOCALSCRATCHDIR;
import static org.apache.hadoop.hive.conf.HiveConf.ConfVars.METASTORECONNECTURLKEY;
import static org.apache.hadoop.hive.conf.HiveConf.ConfVars.METASTOREWAREHOUSE;
import static org.apache.hadoop.hive.conf.HiveConf.ConfVars.METASTORE_VALIDATE_COLUMNS;
import static org.apache.hadoop.hive.conf.HiveConf.ConfVars.METASTORE_VALIDATE_CONSTRAINTS;
import static org.apache.hadoop.hive.conf.HiveConf.ConfVars.METASTORE_VALIDATE_TABLES;
import static org.apache.hadoop.hive.conf.HiveConf.ConfVars.SCRATCHDIR;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;

import org.apache.hadoop.fs.FileUtil;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.tez.dag.api.TezConfiguration;
import org.apache.tez.runtime.library.api.TezRuntimeConfiguration;
import org.junit.rules.TemporaryFolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.klarna.hiverunner.config.HiveRunnerConfig;

/**
 * Responsible for common configuration for running the HiveServer within this JVM with zero external dependencies.
 * <p>
 * This class contains a bunch of methods meant to be overridden in order to create slightly different contexts.
 * </p><p>
 * This context configures HiveServer for both mr and tez. There's nothing contradicting with those configurations so
 * they may coexist in order to allow test cases to alter execution engines within the same test by
 * e.g: 'set hive.execution.engine=tez;'.
 * </p>
 */
public class StandaloneHiveServerContext implements HiveServerContext {

  private static final Logger LOGGER = LoggerFactory.getLogger(StandaloneHiveServerContext.class);
  private final TemporaryFolder basedir;
  private final HiveRunnerConfig hiveRunnerConfig;
  private final HiveConf hiveConf = new HiveConf();
  private String metaStorageUrl;

  StandaloneHiveServerContext(TemporaryFolder basedir, HiveRunnerConfig hiveRunnerConfig) {
    this.basedir = basedir;
    this.hiveRunnerConfig = hiveRunnerConfig;
  }

  @Override
  public final void init() {

    configureMiscHiveSettings(hiveConf);

    configureMetaStore(hiveConf);

    configureMrExecutionEngine(hiveConf);

    configureTezExecutionEngine(hiveConf);

    configureJavaSecurityRealm(hiveConf);

    configureSupportConcurrency(hiveConf);

    configureFileSystem(basedir, hiveConf);

    configureAssertionStatus(hiveConf);

    overrideHiveConf(hiveConf);
  }

  private void configureMiscHiveSettings(HiveConf hiveConf) {
    hiveConf.setBoolVar(HIVESTATSAUTOGATHER, false);

    // Turn of dependency to calcite library
    hiveConf.setBoolVar(HIVE_CBO_ENABLED, false);

    // Disable to get rid of clean up exception when stopping the Session.
    hiveConf.setBoolVar(HIVE_SERVER2_LOGGING_OPERATION_ENABLED, false);

    hiveConf.setVar(HADOOPBIN, "NO_BIN!");
  }

  private void overrideHiveConf(HiveConf hiveConf) {
    for (Map.Entry<String, String> hiveConfEntry : hiveRunnerConfig.getHiveConfSystemOverride().entrySet()) {
      hiveConf.set(hiveConfEntry.getKey(), hiveConfEntry.getValue());
    }
  }

  private void configureMrExecutionEngine(HiveConf conf) {

    /*
     * Switch off all optimizers otherwise we didn't
     * manage to contain the map reduction within this JVM.
     */
    conf.setBoolVar(HIVE_INFER_BUCKET_SORT, false);
    conf.setBoolVar(HIVEMETADATAONLYQUERIES, false);
    conf.setBoolVar(HIVEOPTINDEXFILTER, false);
    conf.setBoolVar(HIVECONVERTJOIN, false);
    conf.setBoolVar(HIVESKEWJOIN, false);

    // Defaults to a 1000 millis sleep in. We can speed up the tests a bit by setting this to 1 millis instead.
    // org.apache.hadoop.hive.ql.exec.mr.HadoopJobExecHelper.
    hiveConf.setLongVar(HiveConf.ConfVars.HIVECOUNTERSPULLINTERVAL, 1L);

    hiveConf.setBoolVar(HiveConf.ConfVars.HIVE_RPC_QUERY_PLAN, true);
  }

  private void configureTezExecutionEngine(HiveConf conf) {
        /*
        Tez local mode settings
         */
    conf.setBoolean(TezConfiguration.TEZ_LOCAL_MODE, true);
    conf.set("fs.defaultFS", "file:///");
    conf.setBoolean(TezRuntimeConfiguration.TEZ_RUNTIME_OPTIMIZE_LOCAL_FETCH, true);

        /*
        Set to be able to run tests offline
         */
    conf.set(TezConfiguration.TEZ_AM_DISABLE_CLIENT_VERSION_CHECK, "true");

        /*
        General attempts to strip of unnecessary functionality to speed up test execution and increase stability
         */
    conf.set(TezConfiguration.TEZ_AM_USE_CONCURRENT_DISPATCHER, "false");
    conf.set(TezConfiguration.TEZ_AM_CONTAINER_REUSE_ENABLED, "false");
    conf.set(TezConfiguration.DAG_RECOVERY_ENABLED, "false");
    conf.set(TezConfiguration.TEZ_TASK_GET_TASK_SLEEP_INTERVAL_MS_MAX, "1");
    conf.set(TezConfiguration.TEZ_AM_WEBSERVICE_ENABLE, "false");
    conf.set(TezConfiguration.DAG_RECOVERY_ENABLED, "false");
    conf.set(TezConfiguration.TEZ_AM_NODE_BLACKLISTING_ENABLED, "false");
  }

  private void configureJavaSecurityRealm(HiveConf hiveConf) {
    // These three properties gets rid of: 'Unable to load realm info from SCDynamicStore'
    // which seems to have a timeout of about 5 secs.
    System.setProperty("java.security.krb5.realm", "");
    System.setProperty("java.security.krb5.kdc", "");
    System.setProperty("java.security.krb5.conf", "/dev/null");
  }

  private void configureAssertionStatus(HiveConf conf) {
    ClassLoader.getSystemClassLoader().setPackageAssertionStatus("org.apache.hadoop.hive.serde2.objectinspector",
        false);
  }

  private void configureSupportConcurrency(HiveConf conf) {
    hiveConf.setBoolVar(HIVE_SUPPORT_CONCURRENCY, false);
  }

  private void configureMetaStore(HiveConf conf) {
    configureDerbyLog();

    String jdbcDriver = org.apache.derby.jdbc.EmbeddedDriver.class.getName();
    try {
      Class.forName(jdbcDriver);
    } catch (ClassNotFoundException e) {
      throw new RuntimeException(e);
    }

    // Set the Hive Metastore DB driver
    metaStorageUrl = "jdbc:derby:memory:" + UUID.randomUUID().toString();
    hiveConf.set("datanucleus.schema.autoCreateAll", "true");
    hiveConf.set("hive.metastore.schema.verification", "false");

    hiveConf.set("datanucleus.connectiondrivername", jdbcDriver);
    hiveConf.set("javax.jdo.option.ConnectionDriverName", jdbcDriver);

    // No pooling needed. This will save us a lot of threads
    hiveConf.set("datanucleus.connectionPoolingType", "None");

    conf.setBoolVar(METASTORE_VALIDATE_CONSTRAINTS, true);
    conf.setBoolVar(METASTORE_VALIDATE_COLUMNS, true);
    conf.setBoolVar(METASTORE_VALIDATE_TABLES, true);
  }

  private void configureDerbyLog() {
    // overriding default derby log path to not go to root of project
    File derbyLogFile;
    try {
      derbyLogFile = File.createTempFile("derby", ".log");
      LOGGER.debug("Derby set to log to " + derbyLogFile.getAbsolutePath());
    } catch (IOException e) {
      throw new RuntimeException("Error creating temporary derby log file", e);
    }
    System.setProperty("derby.stream.error.file", derbyLogFile.getAbsolutePath());
  }

  private void configureFileSystem(TemporaryFolder basedir, HiveConf conf) {
    conf.setVar(METASTORECONNECTURLKEY, metaStorageUrl + ";create=true");

    createAndSetFolderProperty(METASTOREWAREHOUSE, "warehouse", conf, basedir);
    createAndSetFolderProperty(SCRATCHDIR, "scratchdir", conf, basedir);
    createAndSetFolderProperty(LOCALSCRATCHDIR, "localscratchdir", conf, basedir);
    createAndSetFolderProperty(HIVEHISTORYFILELOC, "tmp", conf, basedir);

    conf.setBoolVar(HIVE_WAREHOUSE_SUBDIR_INHERIT_PERMS, true);

    createAndSetFolderProperty("hadoop.tmp.dir", "hadooptmp", conf, basedir);
    createAndSetFolderProperty("test.log.dir", "logs", conf, basedir);



        /*
            Tez specific configurations below
         */
        /*
            Tez will upload a hive-exec.jar to this location.
            It looks like it will do this only once per test suite so it makes sense to keep this in a central location
            rather than in the tmp dir of each test.
         */
    File installation_dir = newFolder(getBaseDir(), "tez_installation_dir");

    conf.setVar(HiveConf.ConfVars.HIVE_JAR_DIRECTORY, installation_dir.getAbsolutePath());
    conf.setVar(HiveConf.ConfVars.HIVE_USER_INSTALL_DIR, installation_dir.getAbsolutePath());
  }

  private File newFolder(TemporaryFolder basedir, String folder) {
    try {
      File newFolder = basedir.newFolder(folder);
      FileUtil.setPermission(newFolder, FsPermission.getDirDefault());
      return newFolder;
    } catch (IOException e) {
      throw new IllegalStateException("Failed to create tmp dir: " + e.getMessage(), e);
    }
  }

  @Override
  public HiveConf getHiveConf() {
    return hiveConf;
  }

  @Override
  public TemporaryFolder getBaseDir() {
    return basedir;
  }

  private final void createAndSetFolderProperty(HiveConf.ConfVars var, String folder, HiveConf conf,
      TemporaryFolder basedir) {
    conf.setVar(var, newFolder(basedir, folder).getAbsolutePath());
  }

  private final void createAndSetFolderProperty(String key, String folder, HiveConf conf, TemporaryFolder basedir) {
    conf.set(key, newFolder(basedir, folder).getAbsolutePath());
  }
}