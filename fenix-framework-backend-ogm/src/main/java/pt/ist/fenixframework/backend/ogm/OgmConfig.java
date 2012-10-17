package pt.ist.fenixframework.backend.ogm;

import org.apache.log4j.Logger;

import pt.ist.fenixframework.Config;
import pt.ist.fenixframework.ConfigError;
import pt.ist.fenixframework.DomainObject;
import pt.ist.fenixframework.core.IdentityMap;

/**
 * This is the ogm configuration manager used by the fenix-framework-backend-ogm project.
 * 
 * @see Config
 *
 */
public class OgmConfig extends Config {
    private static final Logger logger = Logger.getLogger(OgmDomainObject.class);

    // /**
    //  * This <strong>required</strong> parameter specifies the location of the XML file used to
    //  * configure Infinispan.  This file should be available in the application's classpath.
    //  */
    // protected String ispnConfigFile = null;

    protected final OgmBackEnd backEnd;

    public OgmConfig() {
        this.backEnd = OgmBackEnd.getInstance();
    }

    // process this config's parameters

    // protected void identityMapFromString(String value) {
    //     String cleanValue = value.trim().toUpperCase();
    //     try {
    //         identityMap = MapType.valueOf(cleanValue);
    //     } catch (IllegalArgumentException e) {
    //         String message = "Unknown value for configuration property 'identityMap': " + value;
    //         logger.fatal(message);
    //         throw new ConfigError(message, e);
    //     }
    // }

    // public String getIspnConfigFile() {
    //     return this.ispnConfigFile;
    // }

    @Override
    protected void init() {
        this.backEnd.configOgm(this);
        // DomainClassInfo.initializeClassInfos(FenixFramework.getDomainModel(), 0);
    }

    // @Override
    // protected void checkConfig() {
    //     super.checkConfig();
    //     if (ispnConfigFile == null) {
    //         missingRequired("ispnConfigFile");
    //     }
    // }

    @Override
    public OgmBackEnd getBackEnd() {
        return this.backEnd;
    }
}
