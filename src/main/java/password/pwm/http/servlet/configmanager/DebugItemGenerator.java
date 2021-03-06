/*
 * Password Management Servlets (PWM)
 * http://code.google.com/p/pwm/
 *
 * Copyright (c) 2006-2009 Novell, Inc.
 * Copyright (c) 2009-2015 The PWM Project
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 *
 */

package password.pwm.http.servlet.configmanager;

import com.novell.ldapchai.ChaiEntry;
import com.novell.ldapchai.ChaiFactory;
import com.novell.ldapchai.provider.ChaiProvider;
import com.novell.ldapchai.util.ChaiUtility;
import org.apache.commons.csv.CSVPrinter;
import password.pwm.AppProperty;
import password.pwm.PwmAboutProperty;
import password.pwm.PwmApplication;
import password.pwm.PwmConstants;
import password.pwm.config.Configuration;
import password.pwm.config.PwmSetting;
import password.pwm.config.profile.LdapProfile;
import password.pwm.config.stored.StoredConfigurationImpl;
import password.pwm.error.PwmUnrecoverableException;
import password.pwm.health.HealthRecord;
import password.pwm.http.PwmRequest;
import password.pwm.ldap.LdapOperationsHelper;
import password.pwm.svc.PwmService;
import password.pwm.util.FileSystemUtility;
import password.pwm.util.Helper;
import password.pwm.util.JsonUtil;
import password.pwm.util.LDAPPermissionCalculator;
import password.pwm.util.logging.LocalDBLogger;
import password.pwm.util.logging.PwmLogEvent;
import password.pwm.util.logging.PwmLogLevel;
import password.pwm.util.logging.PwmLogger;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class DebugItemGenerator {
    private static final PwmLogger LOGGER = PwmLogger.forClass(DebugItemGenerator.class);

    private static final List<Class<? extends Generator>> DEBUG_ZIP_ITEM_GENERATORS  = Collections.unmodifiableList(Arrays.asList(
            ConfigurationFileItemGenerator.class,
            ConfigurationDebugJsonItemGenerator.class,
            ConfigurationDebugTextItemGenerator.class,
            AboutItemGenerator.class,
            EnvironmentItemGenerator.class,
            AppPropertiesItemGenerator.class,
            AuditDebugItemGenerator.class,
            InfoDebugItemGenerator.class,
            HealthDebugItemGenerator.class,
            ThreadDumpDebugItemGenerator.class,
            FileInfoDebugItemGenerator.class,
            LogDebugItemGenerator.class,
            LdapDebugItemGenerator.class,
            LDAPPermissionItemGenerator.class
    ));

   static void outputZipDebugFile(
            final PwmRequest pwmRequest,
            final ZipOutputStream zipOutput,
            final String pathPrefix
    )
            throws IOException, PwmUnrecoverableException
    {
        final PwmApplication pwmApplication = pwmRequest.getPwmApplication();

        { // kick off health check so that it might be faster later..
            Thread healthThread = new Thread() {
                public void run() {
                    pwmApplication.getHealthMonitor().getHealthRecords();
                }
            };
            healthThread.setName(Helper.makeThreadName(pwmApplication, ConfigManagerServlet.class) + "-HealthCheck");
            healthThread.setDaemon(true);
            healthThread.start();
        }


        for (final Class<? extends DebugItemGenerator.Generator> serviceClass : DEBUG_ZIP_ITEM_GENERATORS) {
            try {
                LOGGER.trace(pwmRequest, "beginning debug output of item " + serviceClass.getSimpleName());
                final Object newInstance = serviceClass.newInstance();
                final DebugItemGenerator.Generator newGeneratorItem = (DebugItemGenerator.Generator)newInstance;
                zipOutput.putNextEntry(new ZipEntry(pathPrefix + newGeneratorItem.getFilename()));
                newGeneratorItem.outputItem(pwmApplication, pwmRequest, zipOutput);
                zipOutput.closeEntry();
                zipOutput.flush();
            } catch (Exception e) {
                final String errorMsg = "unexpected error executing debug item output class '" + serviceClass.getName() + "', error: " + e.toString();
                LOGGER.error(pwmRequest, errorMsg);
            }
        }
    }

    static class ConfigurationDebugJsonItemGenerator implements Generator {
        @Override
        public String getFilename() {
            return "configuration-debug.json";
        }

        @Override
        public void outputItem(PwmApplication pwmApplication, PwmRequest pwmRequest, OutputStream outputStream) throws Exception
        {
            final StoredConfigurationImpl storedConfiguration = ConfigManagerServlet.readCurrentConfiguration(pwmRequest);
            storedConfiguration.resetAllPasswordValues("value removed from " + PwmConstants.PWM_APP_NAME + "-Support configuration export");
            final String jsonOutput = JsonUtil.serialize(storedConfiguration.toJsonDebugObject(), JsonUtil.Flag.PrettyPrint);
            outputStream.write(jsonOutput.getBytes(PwmConstants.DEFAULT_CHARSET));
        }
    }

    static class ConfigurationDebugTextItemGenerator implements Generator {
        @Override
        public String getFilename() {
            return "configuration-debug.txt";
        }

        @Override
        public void outputItem(PwmApplication pwmApplication, PwmRequest pwmRequest, OutputStream outputStream) throws Exception
        {
            final StoredConfigurationImpl storedConfiguration = ConfigManagerServlet.readCurrentConfiguration(pwmRequest);
            storedConfiguration.resetAllPasswordValues("value removed from " + PwmConstants.PWM_APP_NAME + "-Support configuration export");

            final StringWriter writer = new StringWriter();
            writer.write("Configuration Debug Output for "
                    + PwmConstants.PWM_APP_NAME + " "
                    + PwmConstants.SERVLET_VERSION + "\n");
            writer.write("Timestamp: " + PwmConstants.DEFAULT_DATETIME_FORMAT.format(storedConfiguration.modifyTime()) + "\n");
            writer.write("This file is encoded using " + PwmConstants.DEFAULT_CHARSET.displayName() + "\n");

            writer.write("\n");
            final Map<String,String> modifiedSettings = storedConfiguration.getModifiedSettingDebugValues(PwmConstants.DEFAULT_LOCALE, true);
            for (final String key : modifiedSettings.keySet()) {
                final String value = modifiedSettings.get(key);
                writer.write(">> Setting > " + key);
                writer.write("\n");
                writer.write(value);
                writer.write("\n");
                writer.write("\n");
            }

            outputStream.write(writer.toString().getBytes(PwmConstants.DEFAULT_CHARSET));
        }
    }

    static class ConfigurationFileItemGenerator implements Generator {
        @Override
        public String getFilename() {
            return PwmConstants.DEFAULT_CONFIG_FILE_FILENAME;
        }

        @Override
        public void outputItem(PwmApplication pwmApplication, PwmRequest pwmRequest, OutputStream outputStream) throws Exception
        {
            final StoredConfigurationImpl storedConfiguration = ConfigManagerServlet.readCurrentConfiguration(pwmRequest);
            storedConfiguration.resetAllPasswordValues("value removed from " + PwmConstants.PWM_APP_NAME + "-Support configuration export");

            final ByteArrayOutputStream baos = new ByteArrayOutputStream();
            storedConfiguration.toXml(baos);
            outputStream.write(baos.toByteArray());
        }
    }

    static class AboutItemGenerator implements Generator {
        @Override
        public String getFilename() {
            return "about.properties";
        }

        @Override
        public void outputItem(PwmApplication pwmApplication, PwmRequest pwmRequest, OutputStream outputStream) throws Exception
        {
            final Properties outputProps = new Properties() {
                public synchronized Enumeration<Object> keys() {
                    return Collections.enumeration(new TreeSet<>(super.keySet()));
                }
            };

            final Map<PwmAboutProperty,String> infoBean = Helper.makeInfoBean(pwmApplication);
            for (final PwmAboutProperty aboutProperty : infoBean.keySet()) {
                outputProps.put(aboutProperty.toString().replace("_","."), infoBean.get(aboutProperty));
            }
            final ByteArrayOutputStream baos = new ByteArrayOutputStream();
            outputProps.store(baos, PwmConstants.DEFAULT_DATETIME_FORMAT.format(new Date()));
            outputStream.write(baos.toByteArray());
        }
    }

    static class EnvironmentItemGenerator implements Generator {
        @Override
        public String getFilename() {
            return "environment.properties";
        }

        @Override
        public void outputItem(PwmApplication pwmApplication, PwmRequest pwmRequest, OutputStream outputStream) throws Exception
        {
            final Properties outputProps = Helper.newSortedProperties();

            // java threads
            final Map<String,String> envProps = System.getenv();
            for (final String key : envProps.keySet()) {
                outputProps.put(key, envProps.get(key));
            }
            final ByteArrayOutputStream baos = new ByteArrayOutputStream();
            outputProps.store(baos,PwmConstants.DEFAULT_DATETIME_FORMAT.format(new Date()));
            outputStream.write(baos.toByteArray());
        }
    }

    static class AppPropertiesItemGenerator implements Generator {
        @Override
        public String getFilename() {
            return "appProperties.properties";
        }

        @Override
        public void outputItem(PwmApplication pwmApplication, PwmRequest pwmRequest, OutputStream outputStream) throws Exception
        {

            final Configuration config = pwmRequest.getConfig();
            final Properties outputProps = Helper.newSortedProperties();

            for (final AppProperty appProperty : AppProperty.values()) {
                outputProps.setProperty(appProperty.getKey(), config.readAppProperty(appProperty));
            }

            final ByteArrayOutputStream baos = new ByteArrayOutputStream();
            outputProps.store(baos,PwmConstants.DEFAULT_DATETIME_FORMAT.format(new Date()));
            outputStream.write(baos.toByteArray());
        }
    }

    static class AuditDebugItemGenerator implements Generator {
        @Override
        public String getFilename() {
            return "audit.csv";
        }

        @Override
        public void outputItem(PwmApplication pwmApplication, PwmRequest pwmRequest, OutputStream outputStream) throws Exception
        {
            final ByteArrayOutputStream baos = new ByteArrayOutputStream();
            pwmApplication.getAuditManager().outputVaultToCsv(baos, pwmRequest.getLocale(), true);
            outputStream.write(baos.toByteArray());
        }
    }

    static class InfoDebugItemGenerator implements Generator {
        @Override
        public String getFilename() {
            return "info.json";
        }

        @Override
        public void outputItem(PwmApplication pwmApplication, PwmRequest pwmRequest, OutputStream outputStream) throws Exception
        {
            final LinkedHashMap<String,Object> outputMap = new LinkedHashMap<>();

            { // services info
                final LinkedHashMap<String,Object> servicesMap = new LinkedHashMap<>();
                for (final PwmService service : pwmApplication.getPwmServices()) {
                    final LinkedHashMap<String,Object> serviceOutput = new LinkedHashMap<>();
                    serviceOutput.put("name", service.getClass().getSimpleName());
                    serviceOutput.put("status",service.status());
                    serviceOutput.put("health",service.healthCheck());
                    serviceOutput.put("serviceInfo",service.serviceInfo());
                    servicesMap.put(service.getClass().getSimpleName(), serviceOutput);
                }
                outputMap.put("services",servicesMap);
            }

            final String recordJson = JsonUtil.serializeMap(outputMap, JsonUtil.Flag.PrettyPrint);
            outputStream.write(recordJson.getBytes(PwmConstants.DEFAULT_CHARSET));
        }
    }


    static class HealthDebugItemGenerator implements Generator {
        @Override
        public String getFilename() {
            return "health.json";
        }

        @Override
        public void outputItem(PwmApplication pwmApplication, PwmRequest pwmRequest, OutputStream outputStream) throws Exception {
            final Set<HealthRecord> records = pwmApplication.getHealthMonitor().getHealthRecords();
            final String recordJson = JsonUtil.serializeCollection(records, JsonUtil.Flag.PrettyPrint);
            outputStream.write(recordJson.getBytes(PwmConstants.DEFAULT_CHARSET));
        }
    }

    static class ThreadDumpDebugItemGenerator implements Generator {
        @Override
        public String getFilename() {
            return "threads.txt";
        }

        @Override
        public void outputItem(PwmApplication pwmApplication, PwmRequest pwmRequest, OutputStream outputStream) throws Exception {

            final ByteArrayOutputStream baos = new ByteArrayOutputStream();
            final PrintWriter writer = new PrintWriter( new OutputStreamWriter(baos, PwmConstants.DEFAULT_CHARSET) );
            final ThreadInfo[] threads = ManagementFactory.getThreadMXBean().dumpAllThreads(true,true);
            for (final ThreadInfo threadInfo : threads) {
                writer.write(threadInfo.toString());
            }
            writer.flush();
            outputStream.write(baos.toByteArray());
        }
    }

    static class LdapDebugItemGenerator implements Generator {
        @Override
        public String getFilename() {
            return "ldap.txt";
        }

        @Override
        public void outputItem(PwmApplication pwmApplication, PwmRequest pwmRequest, OutputStream outputStream) throws Exception {
            final Writer writer = new OutputStreamWriter(outputStream, PwmConstants.DEFAULT_CHARSET);
            for (LdapProfile ldapProfile : pwmApplication.getConfig().getLdapProfiles().values()) {
                writer.write("ldap profile: " + ldapProfile.getIdentifier() + "\n");
                try {
                    final ChaiProvider chaiProvider = ldapProfile.getProxyChaiProvider(pwmApplication);
                    {
                        final ChaiEntry rootDSEentry = ChaiUtility.getRootDSE(chaiProvider);
                        final Map<String, List<String>> rootDSEdata = LdapOperationsHelper.readAllEntryAttributeValues(rootDSEentry);
                        writer.write("Root DSE: " + JsonUtil.serializeMap(rootDSEdata, JsonUtil.Flag.PrettyPrint) + "\n");
                    }
                    {
                        final String proxyUserDN = ldapProfile.readSettingAsString(PwmSetting.LDAP_PROXY_USER_DN);
                        final ChaiEntry proxyUserEntry = ChaiFactory.createChaiEntry(proxyUserDN, chaiProvider);
                        final Map<String, List<String>> proxyUserData = LdapOperationsHelper.readAllEntryAttributeValues(proxyUserEntry);
                        writer.write("Proxy User: " + JsonUtil.serializeMap(proxyUserData, JsonUtil.Flag.PrettyPrint) + "\n");
                    }
                    {
                        final String testUserDN = ldapProfile.readSettingAsString(PwmSetting.LDAP_PROXY_USER_DN);
                        if (testUserDN != null) {
                            final ChaiEntry testUserEntry = ChaiFactory.createChaiEntry(testUserDN, chaiProvider);
                            if (testUserEntry.isValid()) {
                                final Map<String, List<String>> testUserdata = LdapOperationsHelper.readAllEntryAttributeValues(testUserEntry);
                                writer.write("Test User: " + JsonUtil.serializeMap(testUserdata, JsonUtil.Flag.PrettyPrint) + "\n");
                            }
                        }
                    }
                    writer.write("\n\n");
                } catch (Exception e) {
                    LOGGER.error("error during output of ldap profile debug data profile: " + ldapProfile + ", error: " + e.getMessage());
                }
            }

            writer.flush();
        }
    }

    static class FileInfoDebugItemGenerator implements Generator {
        @Override
        public String getFilename() {
            return "fileinformation.csv";
        }

        @Override
        public void outputItem(PwmApplication pwmApplication, PwmRequest pwmRequest, OutputStream outputStream) throws Exception {
            final List<FileSystemUtility.FileSummaryInformation> fileSummaryInformations = new ArrayList<>();
            final File applicationPath = pwmApplication.getPwmEnvironment().getApplicationPath();

            if (pwmApplication.getPwmEnvironment().getContextManager() != null) {
                try {
                    final File webInfPath = pwmApplication.getPwmEnvironment().getContextManager().locateWebInfFilePath();
                    if (webInfPath != null && webInfPath.exists()) {
                        final File servletRootPath = webInfPath.getParentFile();

                        if (servletRootPath != null) {
                            fileSummaryInformations.addAll(FileSystemUtility.readFileInformation(webInfPath));
                        }
                    }
                } catch (Exception e) {
                    LOGGER.error(pwmRequest, "unable to generate webInfPath fileMd5sums during zip debug building: " + e.getMessage());
                }
            }

            if (applicationPath != null ) {
                try {
                    fileSummaryInformations.addAll(FileSystemUtility.readFileInformation(applicationPath));
                } catch (Exception e) {
                    LOGGER.error(pwmRequest, "unable to generate appPath fileMd5sums during zip debug building: " + e.getMessage());
                }
            }

            {
                final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                final CSVPrinter csvPrinter = Helper.makeCsvPrinter(byteArrayOutputStream);
                {
                    final List<String> headerRow = new ArrayList<>();
                    headerRow.add("Filename");
                    headerRow.add("Filepath");
                    headerRow.add("Last Modified");
                    headerRow.add("Size");
                    headerRow.add("sha1sum");
                    csvPrinter.printRecord(headerRow);
                }
                for (final FileSystemUtility.FileSummaryInformation fileSummaryInformation : fileSummaryInformations) {
                    final List<String> dataRow = new ArrayList<>();
                    dataRow.add(fileSummaryInformation.getFilename());
                    dataRow.add(fileSummaryInformation.getFilepath());
                    dataRow.add(PwmConstants.DEFAULT_DATETIME_FORMAT.format(fileSummaryInformation.getModified()));
                    dataRow.add(String.valueOf(fileSummaryInformation.getSize()));
                    dataRow.add(fileSummaryInformation.getSha1sum());
                    csvPrinter.printRecord(dataRow);
                }
                csvPrinter.flush();
                outputStream.write(byteArrayOutputStream.toByteArray());
            }
        }
    }

    static class LogDebugItemGenerator implements Generator {
        @Override
        public String getFilename() {
            return "debug.log";
        }

        @Override
        public void outputItem(
                final PwmApplication pwmApplication,
                final PwmRequest pwmRequest,
                final OutputStream outputStream
        ) throws Exception {

            final int maxCount = Integer.parseInt(pwmRequest.getConfig().readAppProperty(AppProperty.CONFIG_MANAGER_ZIPDEBUG_MAXLOGLINES));
            final int maxSeconds = Integer.parseInt(pwmRequest.getConfig().readAppProperty(AppProperty.CONFIG_MANAGER_ZIPDEBUG_MAXLOGSECONDS));
            final LocalDBLogger.SearchParameters searchParameters = new LocalDBLogger.SearchParameters(
                    PwmLogLevel.TRACE,
                    maxCount,
                    null,
                    null,
                    (maxSeconds * 1000),
                    null
            );
            final LocalDBLogger.SearchResults searchResults = pwmApplication.getLocalDBLogger().readStoredEvents(
                    searchParameters);
            int counter = 0;
            while (searchResults.hasNext()) {
                final PwmLogEvent event = searchResults.next();
                outputStream.write(event.toLogString().getBytes(PwmConstants.DEFAULT_CHARSET));
                outputStream.write("\n".getBytes(PwmConstants.DEFAULT_CHARSET));
                counter++;
                if (counter % 1000 == 0) {
                    outputStream.flush();
                }
            }
            LOGGER.trace("output " + counter + " lines to " + this.getFilename());
        }
    }

    static class LDAPPermissionItemGenerator implements Generator {
        @Override
        public String getFilename() {
            return "ldapPermissionSuggestions.csv";
        }

        @Override
        public void outputItem(
                final PwmApplication pwmApplication,
                final PwmRequest pwmRequest,
                final OutputStream outputStream
        ) throws Exception {

            final StoredConfigurationImpl storedConfiguration = ConfigManagerServlet.readCurrentConfiguration(pwmRequest);
            final LDAPPermissionCalculator ldapPermissionCalculator = new LDAPPermissionCalculator(storedConfiguration);

            final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            final CSVPrinter csvPrinter = Helper.makeCsvPrinter(byteArrayOutputStream);
            {
                final List<String> headerRow = new ArrayList<>();
                headerRow.add("Attribute");
                headerRow.add("Actor");
                headerRow.add("Access");
                headerRow.add("Setting");
                headerRow.add("Profile");
                csvPrinter.printRecord(headerRow);
            }

            for (final LDAPPermissionCalculator.PermissionRecord record : ldapPermissionCalculator.getPermissionRecords()) {
                final List<String> dataRow = new ArrayList<>();
                dataRow.add(record.getAttribute());
                dataRow.add(record.getActor().toString());
                dataRow.add(record.getAccess().toString());
                dataRow.add(record.getPwmSetting().getKey());
                dataRow.add(record.getProfile() == null ? "" : record.getProfile());
                csvPrinter.printRecord(dataRow);
            }
        }
    }

    interface Generator {

        String getFilename();

        void outputItem(
                PwmApplication pwmApplication,
                PwmRequest pwmRequest,
                OutputStream outputStream
        ) throws Exception;
    }
}
