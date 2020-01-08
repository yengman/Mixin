/*
 * This file is part of Mixin, licensed under the MIT License (MIT).
 *
 * Copyright (c) SpongePowered <https://www.spongepowered.org>
 * Copyright (c) contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.spongepowered.asm.mixin.transformer;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.launch.MixinInitialisationError;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.MixinEnvironment.CompatibilityLevel;
import org.spongepowered.asm.mixin.MixinEnvironment.Option;
import org.spongepowered.asm.mixin.MixinEnvironment.Phase;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.extensibility.IMixinConfig;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.InjectionPoint;
import org.spongepowered.asm.mixin.refmap.IReferenceMapper;
import org.spongepowered.asm.mixin.refmap.ReferenceMapper;
import org.spongepowered.asm.mixin.refmap.RemappingReferenceMapper;
import org.spongepowered.asm.mixin.transformer.throwables.InvalidMixinException;
import org.spongepowered.asm.service.IMixinService;
import org.spongepowered.asm.service.MixinService;
import org.spongepowered.asm.util.VersionNumber;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

/**
 * Mixin configuration bundle
 */
final class MixinConfig implements Comparable<MixinConfig>, IMixinConfig {
    
    /**
     * Wrapper for injection options
     */
    static class InjectorOptions {
        
        @SerializedName("defaultRequire")
        int defaultRequireValue = 0;
        
        @SerializedName("defaultGroup")
        String defaultGroup = "default";
        
        @SerializedName("injectionPoints")
        List<String> injectionPoints;
        
        @SerializedName("maxShiftBy")
        int maxShiftBy = InjectionPoint.DEFAULT_ALLOWED_SHIFT_BY;

        void mergeFrom(InjectorOptions parent) {
            if (this.defaultRequireValue == 0) {
                this.defaultRequireValue = parent.defaultRequireValue;
            }
            if ("default".equals(this.defaultGroup)) {
                this.defaultGroup = parent.defaultGroup;
            }
            if (this.maxShiftBy == InjectionPoint.DEFAULT_ALLOWED_SHIFT_BY) {
                this.maxShiftBy = parent.maxShiftBy;
            }
        }
        
    }
    
    /**
     * Wrapper for overwrite options
     */
    static class OverwriteOptions {
        
        @SerializedName("conformVisibility")
        boolean conformAccessModifiers;
        
        @SerializedName("requireAnnotations")
        boolean requireOverwriteAnnotations;
        
        void mergeFrom(OverwriteOptions parent) {
            this.conformAccessModifiers |= parent.conformAccessModifiers;
            this.requireOverwriteAnnotations |= parent.requireOverwriteAnnotations;
        }
        
    }
    
    /**
     * Callback listener for certain mixin init steps
     */
    interface IListener {

        /**
         * Called when a mixin has been successfully prepared
         * 
         * @param mixin mixin which was prepared
         */
        public abstract void onPrepare(MixinInfo mixin);

        /**
         * Called when a mixin has completed post-initialisation
         * 
         * @param mixin mixin which completed postinit
         */
        public abstract void onInit(MixinInfo mixin);

    }
    
    /**
     * Global order of mixin configs, used to determine ordering between configs
     * with equivalent priority
     */
    private static int configOrder = 0;

    /**
     * Global list of mixin classes, so we can skip any duplicates
     */
    private static final Set<String> globalMixinList = new HashSet<String>();
    
    /**
     * Log even more things
     */
    private final Logger logger = LogManager.getLogger("mixin");
    
    /**
     * Map of mixin target classes to mixin infos
     */
    private final transient Map<String, List<MixinInfo>> mixinMapping = new HashMap<String, List<MixinInfo>>();
    
    /**
     * Targets for this configuration which haven't been mixed yet 
     */
    private final transient Set<String> unhandledTargets = new HashSet<String>();
    
    /**
     * Mixins which have been parsed but not yet prepared 
     */
    private final transient List<MixinInfo> pendingMixins = new ArrayList<MixinInfo>();
    
    /**
     * All mixins loaded by this config 
     */
    private final transient List<MixinInfo> mixins = new ArrayList<MixinInfo>();
    
    /**
     * Marshal 
     */
    private transient Config handle;

    /**
     * Parent config
     */
    private transient MixinConfig parent;

    /**
     * Name of the parent configuration, used to allow inheritance of config
     * options without duplication
     */
    @SerializedName("parent")
    private String parentName;
    
    /**
     * Target selector, eg. &#064;env(DEFAULT)
     */
    @SerializedName("target")
    private String selector;

    /**
     * Minimum version of the mixin subsystem required to correctly apply mixins
     * in this configuration. 
     */
    @SerializedName("minVersion")
    private String version;
    
    /**
     * Minimum compatibility level required for mixins in this set 
     */
    @SerializedName("compatibilityLevel")
    private String compatibility;
    
    /**
     * Determines whether failures in this mixin config are considered terminal
     * errors. Use this setting to indicate that failing to apply a mixin in
     * this config is a critical error and should cause the game to shutdown.
     * Uses boxed boolean so that absent entries can be detected and assigned
     * via parent config where specified.
     */
    @SerializedName("required")
    private Boolean requiredValue;
    
    /**
     * Actual value of required, parsed from the value in the JSON, the
     * environment options, and the parent config where specified.
     */
    private transient boolean required;
    
    /**
     * Configuration priority
     */
    @SerializedName("priority")
    private int priority = -1;
    
    /**
     * Default mixin priority. By default, mixins get a priority of 
     * {@link IMixinConfig#DEFAULT_PRIORITY DEFAULT_PRIORITY} unless a different
     * value is specified in the annotation. This setting allows the base 
     * priority for all mixins in this config to be set to an alternate value.
     */
    @SerializedName("mixinPriority")
    private int mixinPriority = -1;

    /**
     * Package containing all mixins. This package will be monitored by the
     * transformer so that we can explode if some dummy tries to reference a
     * mixin class directly.
     */
    @SerializedName("package")
    private String mixinPackage;
    
    /**
     * Mixin classes to load, mixinPackage will be prepended
     */
    @SerializedName("mixins")
    private List<String> mixinClasses;
    
    /**
     * Mixin classes to load ONLY on client, mixinPackage will be prepended
     */
    @SerializedName("client")
    private List<String> mixinClassesClient;
    
    /**
     * Mixin classes to load ONLY on dedicated server, mixinPackage will be
     * prepended
     */
    @SerializedName("server")
    private List<String> mixinClassesServer;
    
    /**
     * True to set the sourceFile property when applying mixins
     */
    @SerializedName("setSourceFile")
    private boolean setSourceFile = false;
    
    /**
     * The path to the reference map resource to use for this configuration
     */
    @SerializedName("refmap")
    private String refMapperConfig;
    
    /**
     * True to output "mixing in" messages at INFO level rather than DEBUG 
     */
    @SerializedName("verbose")
    private boolean verboseLogging;
    
    /**
     * Intrinsic order (for sorting configurations with identical priority)
     */
    private final transient int order = MixinConfig.configOrder++;
    
    private final transient List<IListener> listeners = new ArrayList<IListener>();
    
//    /**
//     * Phase selector
//     */
//    private transient List<Selector> selectors;
    
    private transient IMixinService service;

    /**
     * Parent environment 
     */
    private transient MixinEnvironment env;
    
    /**
     * Name of the file this config was initialised from
     */
    private transient String name;
    
    /**
     * Name of the {@link IMixinConfigPlugin} to hook onto this MixinConfig 
     */
    @SerializedName("plugin")
    private String pluginClassName;
    
    /**
     * Injector options 
     */
    @SerializedName("injectors")
    private InjectorOptions injectorOptions;
    
    /**
     * Overwrite options 
     */
    @SerializedName("overwrites")
    private OverwriteOptions overwriteOptions;
    
    /**
     * Config plugin, if supplied
     */
    private transient PluginHandle plugin;
    
    /**
     * Reference mapper for injectors
     */
    private transient IReferenceMapper refMapper;

    /**
     * Keep track of initialisation state 
     */
    private transient boolean initialised = false;

    /**
     * Keep track of initialisation state 
     */
    private transient boolean prepared = false;
    
    /**
     * Track whether this mixin has been evaluated for selection yet 
     */
    private transient boolean visited = false;

    /**
     * Spawn via GSON, no public ctor for you 
     */
    private MixinConfig() {}
    
    /**
     * Called immediately after deserialisation
     * 
     * @param name Mixin config name
     * @param fallbackEnvironment Fallback environment if not specified in
     *      config
     * @return true if the config was successfully initialised and should be
     *      returned, or false if initialisation failed and the config should
     *      be discarded
     */
    private boolean onLoad(IMixinService service, String name, MixinEnvironment fallbackEnvironment) {
        this.service = service;
        this.name = name;
        
        // If parent is specified, don't perform postinit until parent is assigned
        if (!Strings.isNullOrEmpty(this.parentName)) {
            return true;
        }
        
        // If no parent, initialise config options
        this.env = this.parseSelector(this.selector, fallbackEnvironment);
        this.required = this.requiredValue != null && this.requiredValue.booleanValue() && !this.env.getOption(Option.IGNORE_REQUIRED);
        this.initPriority(IMixinConfig.DEFAULT_PRIORITY, IMixinConfig.DEFAULT_PRIORITY);
        
        if (this.injectorOptions == null) {
            this.injectorOptions = new InjectorOptions();
        }
        
        if (this.overwriteOptions == null) {
            this.overwriteOptions = new OverwriteOptions();
        }
        
        return this.postInit();
    }

    String getParentName() {
        return this.parentName;
    }
    
    /**
     * Called by outer initialising agent to assign the parent to this config.
     * Copies relevant settings from the parent into the local config object
     * taking into account local overrides.
     * 
     * @param parentConfig parent config handle
     * @return true if version check succeeded
     */
    boolean assignParent(Config parentConfig) {
        if (this.parent != null) {
            throw new MixinInitialisationError("Mixin config " + this.name + " was already initialised");
        }
        
        if (parentConfig.get() == this) {
            throw new MixinInitialisationError("Mixin config " + this.name + " cannot be its own parent");
        }
        
        this.parent = parentConfig.get();
        
        if (!this.parent.initialised) {
            throw new MixinInitialisationError("Mixin config " + this.name + " attempted to assign uninitialised parent config."
                    + " This probably means that there is an indirect loop in the mixin configs: child -> parent -> child");
        }
        
        this.env = this.parseSelector(this.selector, this.parent.env);
        this.required = this.requiredValue == null ? this.parent.required
                : this.requiredValue.booleanValue() && !this.env.getOption(Option.IGNORE_REQUIRED);

        this.initPriority(this.parent.priority, this.parent.mixinPriority);
        
        if (this.injectorOptions == null) {
            this.injectorOptions = this.parent.injectorOptions;
        } else {
            this.injectorOptions.mergeFrom(this.parent.injectorOptions);
        }
        
        if (this.overwriteOptions == null) {
            this.overwriteOptions = this.parent.overwriteOptions;
        } else {
            this.overwriteOptions.mergeFrom(this.parent.overwriteOptions);
        }
        
        this.setSourceFile |= this.parent.setSourceFile;
        this.verboseLogging |= this.parent.verboseLogging;
        
        return this.postInit();
    }

    private void initPriority(int defaultPriority, int defaultMixinPriority) {
        if (this.priority < 0) {
            this.priority = defaultPriority;
        }
        
        if (this.mixinPriority < 0) {
            this.mixinPriority = defaultMixinPriority;
        }
    }

    private boolean postInit() throws MixinInitialisationError {
        if (this.initialised) {
            throw new MixinInitialisationError("Mixin config " + this.name + " was already initialised.");
        }
        
        this.initialised = true;
        this.initCompatibilityLevel();
        this.initInjectionPoints();
        return this.checkVersion();
    }
    
    @SuppressWarnings("deprecation")
    private void initCompatibilityLevel() {
        if (this.compatibility == null) {
            return;
        }
        
        CompatibilityLevel level = CompatibilityLevel.valueOf(this.compatibility.trim().toUpperCase(Locale.ROOT));
        CompatibilityLevel current = MixinEnvironment.getCompatibilityLevel();
        
        if (level == current) {
            return;
        }
        
        // Current level is higher than required but too new to support it
        if (current.isAtLeast(level)) {
            if (!current.canSupport(level)) {
                throw new MixinInitialisationError("Mixin config " + this.name + " requires compatibility level " + level + " which is too old");
            }
        }
        
        // Current level is lower than required but current level prohibits elevation
        if (!current.canElevateTo(level)) {
            throw new MixinInitialisationError("Mixin config " + this.name + " requires compatibility level " + level + " which is prohibited by "
                    + current);
        }
        
        MixinEnvironment.setCompatibilityLevel(level);
    }

    // AMS - temp
    private MixinEnvironment parseSelector(String target, MixinEnvironment fallbackEnvironment) {
        if (target != null) {
            String[] selectors = target.split("[&\\| ]");
            for (String sel : selectors) {
                sel = sel.trim();
                Pattern environmentSelector = Pattern.compile("^@env(?:ironment)?\\(([A-Z]+)\\)$");
                Matcher environmentSelectorMatcher = environmentSelector.matcher(sel);
                if (environmentSelectorMatcher.matches()) {
                    // only parse first env selector
                    return MixinEnvironment.getEnvironment(Phase.forName(environmentSelectorMatcher.group(1)));
                }              
            }
            
            Phase phase = Phase.forName(target);
            if (phase != null) {
                return MixinEnvironment.getEnvironment(phase);
            }
        }
        return fallbackEnvironment;
    }
    
    private void initInjectionPoints() {
        if (this.injectorOptions.injectionPoints == null) {
            return;
        }
        
        for (String injectionPointClassName : this.injectorOptions.injectionPoints) {
            this.initInjectionPoint(injectionPointClassName);
        }
    }

    @SuppressWarnings("unchecked")
    public void initInjectionPoint(String className) {
        try {
            Class<?> injectionPointClass = null;
            try {
                injectionPointClass = this.service.getClassProvider().findClass(className, true);
            } catch (ClassNotFoundException cnfe) {
                this.logger.error("Unable to register injection point {} for {}, the specified class was not found", className, this, cnfe);
                return;
            }
            
            if (!InjectionPoint.class.isAssignableFrom(injectionPointClass)) {
                this.logger.error("Unable to register injection point {} for {}, class must extend InjectionPoint", className, this);
                return;
            }
            
            try {
                injectionPointClass.getDeclaredMethod("find", String.class, InsnList.class, Collection.class);
            } catch (NoSuchMethodException cnfe) {
                this.logger.error("Unable to register injection point {} for {}, the class is not compatible with this version of Mixin",
                        className, this, cnfe);
                return;
            }

            InjectionPoint.register((Class<? extends InjectionPoint>)injectionPointClass);
        } catch (Throwable th) {
            this.logger.catching(th);
        }
    }

    private boolean checkVersion() throws MixinInitialisationError {
        if (this.version == null) {
            // If the parent is non-null, then the version check has already been
            // performed/warned at that level
            if (this.parent != null && this.parent.version != null) {
                return true;
            }
            this.logger.error("Mixin config {} does not specify \"minVersion\" property", this.name);
        }
        
        VersionNumber minVersion = VersionNumber.parse(this.version);
        VersionNumber curVersion = VersionNumber.parse(this.env.getVersion());
        if (minVersion.compareTo(curVersion) > 0) {
            this.logger.warn("Mixin config {} requires mixin subsystem version {} but {} was found. The mixin config will not be applied.",
                    this.name, minVersion, curVersion);
            
            if (this.required) {
                throw new MixinInitialisationError("Required mixin config " + this.name + " requires mixin subsystem version " + minVersion);
            }
            
            return false;
        }
        
        return true;
    }
    
    /**
     * Add a new listener
     * 
     * @param listener listener to add
     */
    void addListener(IListener listener) {
        this.listeners.add(listener);
    }

    /**
     * Initialise the config once it's selected
     */
    void onSelect() {
        this.plugin = new PluginHandle(this, this.service, this.pluginClassName);
        this.plugin.onLoad(this.mixinPackage);

        if (!this.mixinPackage.endsWith(".")) {
            this.mixinPackage += ".";
        }
        
        boolean suppressRefMapWarning = false; 
        
        if (this.refMapperConfig == null) {
            this.refMapperConfig = this.plugin.getRefMapperConfig();
            
            if (this.refMapperConfig == null) {
                suppressRefMapWarning = true;
                this.refMapperConfig = ReferenceMapper.DEFAULT_RESOURCE;
            }
        }
        
        this.refMapper = ReferenceMapper.read(this.refMapperConfig);
        this.verboseLogging |= this.env.getOption(Option.DEBUG_VERBOSE);
        
        if (!suppressRefMapWarning && this.refMapper.isDefault() && !this.env.getOption(Option.DISABLE_REFMAP)) {
            this.logger.warn("Reference map '{}' for {} could not be read. If this is a development environment you can ignore this message",
                    this.refMapperConfig, this);
        }
        
        if (this.env.getOption(Option.REFMAP_REMAP)) {
            this.refMapper = RemappingReferenceMapper.of(this.env, this.refMapper);
        }
    }

    /**
     * <p>Initialisation routine. It's important that we call this routine as
     * late as possible. In general we want to call it on the first call to
     * transform() in the parent transformer. At the very least we want to be
     * called <em>after</em> all the transformers for the current environment
     * have been spawned, because we will run the mixin bytecode through the
     * transformer chain and naturally we want this to happen at a point when we
     * can be reasonably sure that all transfomers have loaded.</p>
     * 
     * <p>For this reason we will invoke the initialisation on the first call to
     * either the <em>hasMixinsFor()</em> or <em>getMixinsFor()</em> methods.
     * </p>
     */
    void prepare() {
        if (this.prepared) {
            return;
        }
        this.prepared = true;
        
        this.prepareMixins(this.mixinClasses, false);
        
        switch (this.env.getSide()) {
            case CLIENT:
                this.prepareMixins(this.mixinClassesClient, false);
                break;
            case SERVER:
                this.prepareMixins(this.mixinClassesServer, false);
                break;
            case UNKNOWN:
                //$FALL-THROUGH$
            default:
                this.logger.warn("Mixin environment was unable to detect the current side, sided mixins will not be applied");
                break;
        }
    }
    
    void postInitialise() {
        if (this.plugin != null) {
            List<String> pluginMixins = this.plugin.getMixins();
            this.prepareMixins(pluginMixins, true);
        }
        
        for (Iterator<MixinInfo> iter = this.mixins.iterator(); iter.hasNext();) {
            MixinInfo mixin = iter.next();
            try {
                mixin.validate();
                for (IListener listener : this.listeners) {
                    listener.onInit(mixin);
                }
            } catch (InvalidMixinException ex) {
                this.logger.error(ex.getMixin() + ": " + ex.getMessage(), ex);
                this.removeMixin(mixin);
                iter.remove();
            } catch (Exception ex) {
                this.logger.error(ex.getMessage(), ex);
                this.removeMixin(mixin);
                iter.remove();
            }
        }
    }

    private void removeMixin(MixinInfo remove) {
        for (List<MixinInfo> mixinsFor : this.mixinMapping.values()) {
            for (Iterator<MixinInfo> iter = mixinsFor.iterator(); iter.hasNext();) {
                if (remove == iter.next()) {
                    iter.remove();
                }
            }
        }
    }

    private void prepareMixins(List<String> mixinClasses, boolean ignorePlugin) {
        if (mixinClasses == null) {
            return;
        }
        
        for (String mixinClass : mixinClasses) {
            String fqMixinClass = this.mixinPackage + mixinClass;
            
            if (mixinClass == null || MixinConfig.globalMixinList.contains(fqMixinClass)) {
                continue;
            }
            
            MixinInfo mixin = null;
            
            try {
                this.pendingMixins.add(mixin = new MixinInfo(this.service, this, mixinClass, this.plugin, ignorePlugin));
                MixinConfig.globalMixinList.add(fqMixinClass);
            } catch (InvalidMixinException ex) {
                if (this.required) {
                    throw ex;
                }
                this.logger.error(ex.getMessage(), ex);
            } catch (Exception ex) {
                if (this.required) {
                    throw new InvalidMixinException(mixin, "Error initialising mixin " + mixin + " - " + ex.getClass() + ": " + ex.getMessage(), ex);
                }
                this.logger.error(ex.getMessage(), ex);
            }
        }
        
        for (MixinInfo mixin : this.pendingMixins) {
            try {
                mixin.parseTargets();
                if (mixin.getTargetClasses().size() > 0) {
                    for (String targetClass : mixin.getTargetClasses()) {
                        String targetClassName = targetClass.replace('/', '.');
                        this.mixinsFor(targetClassName).add(mixin);
                        this.unhandledTargets.add(targetClassName);
                    }
                    for (IListener listener : this.listeners) {
                        listener.onPrepare(mixin);
                    }
                    this.mixins.add(mixin);
                }
            } catch (InvalidMixinException ex) {
                if (this.required) {
                    throw ex;
                }
                this.logger.error(ex.getMessage(), ex);
            } catch (Exception ex) {
                if (this.required) {
                    throw new InvalidMixinException(mixin, "Error initialising mixin " + mixin + " - " + ex.getClass() + ": " + ex.getMessage(), ex);
                }
                this.logger.error(ex.getMessage(), ex);
            }
        }
        
        this.pendingMixins.clear();
    }

    void postApply(String transformedName, ClassNode targetClass) {
        this.unhandledTargets.remove(transformedName);
    }
    
    /**
     * Get marshalling handle
     */
    public Config getHandle() {
        if (this.handle == null) {
            this.handle = new Config(this);
        }
        return this.handle;
    }
    
    /* (non-Javadoc)
     * @see org.spongepowered.asm.mixin.transformer.IMixinConfig#isRequired()
     */
    @Override
    public boolean isRequired() {
        return this.required;
    }
    
    /* (non-Javadoc)
     * @see org.spongepowered.asm.mixin.extensibility.IMixinConfig
     *      #getEnvironment()
     */
    @Override
    public MixinEnvironment getEnvironment() {
        return this.env;
    }
    
    MixinConfig getParent() {
        return this.parent;
    }
    
    /* (non-Javadoc)
     * @see org.spongepowered.asm.mixin.transformer.IMixinConfig#getName()
     */
    @Override
    public String getName() {
        return this.name;
    }

    /**
     * Get the package containing all mixin classes
     */
    @Override
    public String getMixinPackage() {
        return this.mixinPackage;
    }
    
    /**
     * Get the priority
     */
    @Override
    public int getPriority() {
        return this.priority;
    }

    /**
     * Get the default priority for mixins in this config. Values specified in
     * the mixin annotation still override this value
     */
    public int getDefaultMixinPriority() {
        return this.mixinPriority;
    }
    
    /**
     * Get the defined value for the {@link Inject#require} parameter on
     * injectors defined in mixins in this configuration.
     * 
     * @return default require value
     */
    public int getDefaultRequiredInjections() {
        return this.injectorOptions.defaultRequireValue;
    }
    
    /**
     * Get the defined injector group for injectors
     * 
     * @return default group name
     */
    public String getDefaultInjectorGroup() {
        String defaultGroup = this.injectorOptions.defaultGroup;
        return defaultGroup != null && !defaultGroup.isEmpty() ? defaultGroup : "default";
    }
    
    /**
     * Get whether visibility levelfor overwritten methods should be conformed
     * to the target class
     * 
     * @return true if conform is enabled
     */
    public boolean conformOverwriteVisibility() {
        return this.overwriteOptions.conformAccessModifiers;
    }
    
    /**
     * Get whether {@link Overwrite} annotations are required to enable
     * overwrite behaviour for mixins in this config
     * 
     * @return true to require overwriting methods to be annotated
     */
    public boolean requireOverwriteAnnotations() {
        return this.overwriteOptions.requireOverwriteAnnotations;
    }
    
    /**
     * Get the maximum allowed value of {@link At#by}. High values of shift can
     * indicate very brittle injectors and in general should be replaced with
     * slices. This value determines the warning/error threshold (behaviour
     * determined by the environment) for the value of <tt>by</tt>.
     * 
     * @return defined shift warning threshold for this config
     */
    public int getMaxShiftByValue() {
        return Math.min(Math.max(this.injectorOptions.maxShiftBy, 0), InjectionPoint.MAX_ALLOWED_SHIFT_BY);
    }

    // AMS - temp
    public boolean select(MixinEnvironment environment) {
        this.visited = true;
        return this.env == environment;
    }
    
    // AMS - temp
    boolean isVisited() {
        return this.visited;
    }
    
    /**
     * Get the number of mixins in this config, for debug logging
     * 
     * @return total enumerated mixins in set
     */
    int getDeclaredMixinCount() {
        return MixinConfig.getCollectionSize(this.mixinClasses, this.mixinClassesClient, this.mixinClassesServer);
    }

    /**
     * Get the number of mixins actually initialised, for debug logging
     * 
     * @return total enumerated mixins in set
     */
    int getMixinCount() {
        return this.mixins.size();
    }

    /**
     * Get the list of mixin classes we will be applying
     */
    @SuppressWarnings("unchecked")
    public List<String> getClasses() {
        Builder<String> list = ImmutableList.<String>builder();
        for (List<String> classes : new List[] { this.mixinClasses, this.mixinClassesClient, this.mixinClassesServer} ) {
            if (classes != null) {
                for (String className : classes) {
                    list.add(this.mixinPackage + className);
                }
            }
        }
        return list.build();
    }

    /**
     * Get whether to propogate the source file attribute from a mixin onto the
     * target class
     */
    public boolean shouldSetSourceFile() {
        return this.setSourceFile;
    }
    
    /**
     * Get the reference remapper for injectors
     */
    public IReferenceMapper getReferenceMapper() {
        if (this.env.getOption(Option.DISABLE_REFMAP)) {
            return ReferenceMapper.DEFAULT_MAPPER;
        }
        this.refMapper.setContext(this.env.getRefmapObfuscationContext());
        return this.refMapper;
    }
    
    String remapClassName(String className, String reference) {
//        String remapped = this.plugin != null ? this.plugin.remap(className, reference) : null;
//        if (remapped != null) {
//            return remapped;
//        }
        return this.getReferenceMapper().remap(className, reference);
    }
    
    /* (non-Javadoc)
     * @see org.spongepowered.asm.mixin.transformer.IMixinConfig#getPlugin()
     */
    @Override
    public IMixinConfigPlugin getPlugin() {
        return this.plugin.get();
    }

    /* (non-Javadoc)
     * @see org.spongepowered.asm.mixin.transformer.IMixinConfig#getTargets()
     */
    @Override
    public Set<String> getTargets() {
        return Collections.<String>unmodifiableSet(this.mixinMapping.keySet());
    }
    
    /**
     * Get targets for this configuration
     */
    public Set<String> getUnhandledTargets() {
        return Collections.<String>unmodifiableSet(this.unhandledTargets);
    }
    
    /**
     * Get the logging level for this config
     */
    public Level getLoggingLevel() {
        return this.verboseLogging ? Level.INFO : Level.DEBUG;
    }

    /**
     * Get whether this config's package matches the supplied class name
     * 
     * @param className Class name to check
     * @return True if the specified class name is in this config's mixin
     *      package
     */
    public boolean packageMatch(String className) {
        return className.startsWith(this.mixinPackage);
    }
    
    /**
     * Check whether this configuration bundle has a mixin for the specified
     * class
     * 
     * @param targetClass target class
     * @return true if this bundle contains any mixins for the specified target
     */
    public boolean hasMixinsFor(String targetClass) {
        return this.mixinMapping.containsKey(targetClass);
    }
    
    boolean hasPendingMixinsFor(String targetClass) {
        if (this.packageMatch(targetClass)) {
            return false;
        }
        for (MixinInfo pendingMixin : this.pendingMixins) {
            if (pendingMixin.hasDeclaredTarget(targetClass)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Get mixins for the specified target class
     * 
     * @param targetClass target class
     * @return mixins for the specified target
     */
    public List<MixinInfo> getMixinsFor(String targetClass) {
        return this.mixinsFor(targetClass);
    }

    private List<MixinInfo> mixinsFor(String targetClass) {
        List<MixinInfo> mixins = this.mixinMapping.get(targetClass);
        if (mixins == null) {
            mixins = new ArrayList<MixinInfo>();
            this.mixinMapping.put(targetClass, mixins);
        }
        return mixins;
    }

    /**
     * Updates a mixin with new bytecode
     *
     * @param mixinClass Name of the mixin class
     * @param classNode New class
     * @return List of classes that need to be updated
     */
    public List<String> reloadMixin(String mixinClass, ClassNode classNode) {
        for (Iterator<MixinInfo> iter = this.mixins.iterator(); iter.hasNext();) {
            MixinInfo mixin = iter.next();
            if (mixin.getClassName().equals(mixinClass)) {
                mixin.reloadMixin(classNode);
                return mixin.getTargetClasses();
            }
        }
        return Collections.<String>emptyList();
    }
    
    @Override
    public String toString() {
        return this.name;
    }

    /* (non-Javadoc)
     * @see java.lang.Comparable#compareTo(java.lang.Object)
     */
    @Override
    public int compareTo(MixinConfig other) {
        if (other == null) {
            return 0;
        }
        if (other.priority == this.priority) {
            return this.order - other.order;
        }
        return (this.priority - other.priority);
    }
    
    /**
     * Factory method, creates a new mixin configuration bundle from the
     * specified configFile, which must be accessible on the classpath
     * 
     * @param configFile configuration file to load
     * @param outer fallback environment
     * @return new Config
     */
    static Config create(String configFile, MixinEnvironment outer) {
        try {
            IMixinService service = MixinService.getService();
            InputStream resource = service.getResourceAsStream(configFile);
            if (resource == null) {
                throw new IllegalArgumentException(String.format("The specified resource '%s' was invalid or could not be read", configFile));
            }
            MixinConfig config = new Gson().fromJson(new InputStreamReader(resource), MixinConfig.class);
            if (config.onLoad(service, configFile, outer)) {
                return config.getHandle();
            }
            return null;
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException(String.format("The specified resource '%s' was invalid or could not be read", configFile), ex);
        }
    }

    private static int getCollectionSize(Collection<?>... collections) {
        int total = 0;
        for (Collection<?> collection : collections) {
            if (collection != null) {
                total += collection.size();
            }
        }
        return total;
    }

}
