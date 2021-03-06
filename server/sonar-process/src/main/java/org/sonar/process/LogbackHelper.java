/*
 * SonarQube
 * Copyright (C) 2009-2016 SonarSource SA
 * mailto:contact AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.process;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.jul.LevelChangePropagator;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggerContextListener;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.Context;
import ch.qos.logback.core.FileAppender;
import ch.qos.logback.core.joran.spi.JoranException;
import ch.qos.logback.core.rolling.FixedWindowRollingPolicy;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.SizeBasedTriggeringPolicy;
import ch.qos.logback.core.rolling.TimeBasedRollingPolicy;
import java.io.File;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import javax.annotation.CheckForNull;
import org.apache.commons.lang.StringUtils;
import org.slf4j.LoggerFactory;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static org.slf4j.Logger.ROOT_LOGGER_NAME;

/**
 * Helps to configure Logback in a programmatic way, without using XML.
 */
public class LogbackHelper {

  private static final String ALL_LOGS_TO_CONSOLE_PROPERTY = "sonar.log.console";
  private static final String PROCESS_NAME_PLACEHOLDER = "XXXX";
  private static final String THREAD_ID_PLACEHOLDER = "ZZZZ";
  private static final String SONAR_LOG_LEVEL_PROPERTY = "sonar.log.level";
  private static final String SONAR_PROCESS_LOG_LEVEL_PROPERTY = "sonar.log.level." + PROCESS_NAME_PLACEHOLDER;
  private static final String ROLLING_POLICY_PROPERTY = "sonar.log.rollingPolicy";
  private static final String MAX_FILES_PROPERTY = "sonar.log.maxFiles";
  private static final String LOG_FORMAT = "%d{yyyy.MM.dd HH:mm:ss} %-5level " + PROCESS_NAME_PLACEHOLDER + "[" + THREAD_ID_PLACEHOLDER + "][%logger{20}] %msg%n";
  private static final Level[] ALLOWED_ROOT_LOG_LEVELS = new Level[] {Level.TRACE, Level.DEBUG, Level.INFO};

  public static Collection<Level> allowedLogLevels() {
    return Arrays.asList(ALLOWED_ROOT_LOG_LEVELS);
  }

  public LoggerContext getRootContext() {
    org.slf4j.Logger logger;
    while (!((logger = LoggerFactory.getLogger(ROOT_LOGGER_NAME)) instanceof Logger)) {
      // It occurs when the initialization step is still not finished because of a race condition
      // on ILoggerFactory.getILoggerFactory
      // http://jira.qos.ch/browse/SLF4J-167
      // Substitute loggers are used.
      // http://www.slf4j.org/codes.html#substituteLogger
      // Bug is not fixed in SLF4J 1.7.14.
      try {
        Thread.sleep(10);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    return ((Logger) logger).getLoggerContext();
  }

  public LoggerContextListener enableJulChangePropagation(LoggerContext loggerContext) {
    LevelChangePropagator propagator = new LevelChangePropagator();
    propagator.setContext(loggerContext);
    propagator.start();
    loggerContext.addListener(propagator);
    return propagator;
  }

  public static final class RootLoggerConfig {
    private final ProcessId processId;
    private final String threadIdFieldPattern;

    private RootLoggerConfig(Builder builder) {
      this.processId = requireNonNull(builder.processId);
      this.threadIdFieldPattern = builder.threadIdFieldPattern;
    }

    public static Builder newRootLoggerConfigBuilder() {
      return new Builder();
    }

    ProcessId getProcessId() {
      return processId;
    }

    String getThreadIdFieldPattern() {
      return threadIdFieldPattern;
    }

    public static final class Builder {
      @CheckForNull
      private ProcessId processId;
      private String threadIdFieldPattern = "";

      private Builder() {
        // prevents instantiation outside RootLoggerConfig, use static factory method
      }

      public Builder setProcessId(ProcessId processId) {
        this.processId = processId;
        return this;
      }

      public Builder setThreadIdFieldPattern(String threadIdFieldPattern) {
        this.threadIdFieldPattern = requireNonNull(threadIdFieldPattern, "threadIdFieldPattern can't be null");
        return this;
      }

      public RootLoggerConfig build() {
        return new RootLoggerConfig(this);
      }
    }
  }

  public String buildLogPattern(LogbackHelper.RootLoggerConfig config) {
    return LOG_FORMAT
      .replace(PROCESS_NAME_PLACEHOLDER, config.getProcessId().getKey())
      .replace(THREAD_ID_PLACEHOLDER, config.getThreadIdFieldPattern());
  }

  /**
   * Creates a new {@link ConsoleAppender} to {@code System.out} with the specified name and log pattern.
   *
   * @see #buildLogPattern(RootLoggerConfig)
   */
  public ConsoleAppender<ILoggingEvent> newConsoleAppender(Context loggerContext, String name, String logPattern) {
    PatternLayoutEncoder consoleEncoder = new PatternLayoutEncoder();
    consoleEncoder.setContext(loggerContext);
    consoleEncoder.setPattern(logPattern);
    consoleEncoder.start();
    ConsoleAppender<ILoggingEvent> consoleAppender = new ConsoleAppender<>();
    consoleAppender.setContext(loggerContext);
    consoleAppender.setEncoder(consoleEncoder);
    consoleAppender.setName(name);
    consoleAppender.setTarget("System.out");
    consoleAppender.start();
    return consoleAppender;
  }

  /**
   * Make logback configuration for a process to push all its logs to a log file.
   * <p>
   * <ul>
   * <li>the file's name will use the prefix defined in {@link RootLoggerConfig#getProcessId()#getLogFilenamePrefix()}.</li>
   * <li>the file will follow the rotation policy defined in property {@link #ROLLING_POLICY_PROPERTY} and
   * the max number of files defined in property {@link #MAX_FILES_PROPERTY}</li>
   * <li>the logs will follow the specified log pattern</li>
   * </ul>
   * </p>
   *
   * @see #buildLogPattern(RootLoggerConfig)
   */
  public FileAppender<ILoggingEvent> configureGlobalFileLog(Props props, RootLoggerConfig config, String logPattern) {
    LoggerContext ctx = getRootContext();
    Logger rootLogger = ctx.getLogger(ROOT_LOGGER_NAME);
    FileAppender<ILoggingEvent> fileAppender = newFileAppender(ctx, props, config, logPattern);
    rootLogger.addAppender(fileAppender);
    return fileAppender;
  }

  public FileAppender<ILoggingEvent> newFileAppender(LoggerContext ctx, Props props, LogbackHelper.RootLoggerConfig config, String logPattern) {
    RollingPolicy rollingPolicy = createRollingPolicy(ctx, props, config.getProcessId().getLogFilenamePrefix());
    FileAppender<ILoggingEvent> fileAppender = rollingPolicy.createAppender("file_" + config.getProcessId().getLogFilenamePrefix());
    fileAppender.setContext(ctx);
    PatternLayoutEncoder fileEncoder = new PatternLayoutEncoder();
    fileEncoder.setContext(ctx);
    fileEncoder.setPattern(logPattern);
    fileEncoder.start();
    fileAppender.setEncoder(fileEncoder);
    fileAppender.start();
    return fileAppender;
  }

  /**
   * Make the logback configuration for a sub process to correctly push all its logs to be read by a stream gobbler
   * on the sub process's System.out.
   *
   * @see #buildLogPattern(RootLoggerConfig)
   */
  public void configureForSubprocessGobbler(Props props, String logPattern) {
    if (isAllLogsToConsoleEnabled(props)) {
      LoggerContext ctx = getRootContext();
      ctx.getLogger(ROOT_LOGGER_NAME).addAppender(newConsoleAppender(ctx, "root_console", logPattern));
    }
  }

  /**
   * Finds out whether we are in testing environment (usually ITs) and logs of all processes must be forward to
   * App's System.out. This is specified by the value of property {@link #ALL_LOGS_TO_CONSOLE_PROPERTY}.
   */
  public boolean isAllLogsToConsoleEnabled(Props props) {
    return props.valueAsBoolean(ALL_LOGS_TO_CONSOLE_PROPERTY, false);
  }

  /**
   * Configure the log level of the root logger reading the value of property {@link #SONAR_LOG_LEVEL_PROPERTY}.
   *
   * @throws IllegalArgumentException if the value of {@link #SONAR_LOG_LEVEL_PROPERTY} is not one of {@link #ALLOWED_ROOT_LOG_LEVELS}
   */
  public Level configureRootLogLevel(Props props, ProcessId processId) {
    Level newLevel = resolveLevel(props, SONAR_LOG_LEVEL_PROPERTY, SONAR_PROCESS_LOG_LEVEL_PROPERTY.replace(PROCESS_NAME_PLACEHOLDER, processId.getKey()));
    getRootContext().getLogger(ROOT_LOGGER_NAME).setLevel(newLevel);
    return newLevel;
  }

  /**
   * Resolve a log level reading the value of specified properties.
   * <p>
   * To compute the applied log level the following rules will be followed:
   * <ul>the last property with a defined and valid value in the order of the {@code propertyKeys} argument will be applied</ul>
   * <ul>if there is none, {@link Level#INFO INFO} will be returned</ul>
   * </p>
   *
   * @throws IllegalArgumentException if the value of the specified property is not one of {@link #ALLOWED_ROOT_LOG_LEVELS}
   */
  private static Level resolveLevel(Props props, String... propertyKeys) {
    Level newLevel = Level.INFO;
    for (String propertyKey : propertyKeys) {
      Level level = getPropertyValueAsLevel(props, propertyKey);
      if (level != null) {
        newLevel = level;
      }
    }
    return newLevel;
  }

  @CheckForNull
  private static Level getPropertyValueAsLevel(Props props, String propertyKey) {
    String value = props.value(propertyKey);
    if (value == null) {
      return null;
    }

    Level level = Level.toLevel(value, Level.INFO);
    if (!isAllowed(level)) {
      throw new IllegalArgumentException(format("log level %s in property %s is not a supported value (allowed levels are %s)",
        level, propertyKey, Arrays.toString(ALLOWED_ROOT_LOG_LEVELS)));
    }
    return level;
  }

  private static boolean isAllowed(Level level) {
    for (Level allowedRootLogLevel : ALLOWED_ROOT_LOG_LEVELS) {
      if (level == allowedRootLogLevel) {
        return true;
      }
    }
    return false;
  }

  /**
   * Configure the log level of the root logger to the specified level.
   *
   * @throws IllegalArgumentException if the specified level is not one of {@link #ALLOWED_ROOT_LOG_LEVELS}
   */
  public Level configureRootLogLevel(Level newLevel) {
    Logger rootLogger = getRootContext().getLogger(ROOT_LOGGER_NAME);
    ensureSupportedLevel(newLevel);
    rootLogger.setLevel(newLevel);
    return newLevel;
  }

  private static void ensureSupportedLevel(Level newLevel) {
    if (!isAllowed(newLevel)) {
      throw new IllegalArgumentException(format("%s log level is not supported (allowed levels are %s)", newLevel, Arrays.toString(ALLOWED_ROOT_LOG_LEVELS)));
    }
  }

  public Level configureLoggerLogLevelFromDomain(String loggerName, Props props, ProcessId processId, LogDomain domain) {
    return configureLoggersLogLevelFromDomain(Collections.singleton(loggerName), props, processId, domain);
  }

  public Level configureLoggersLogLevelFromDomain(Set<String> loggerNames, Props props, ProcessId processId, LogDomain domain) {
    String processProperty = SONAR_PROCESS_LOG_LEVEL_PROPERTY.replace(PROCESS_NAME_PLACEHOLDER, processId.getKey());
    Level newLevel = resolveLevel(props, SONAR_LOG_LEVEL_PROPERTY, processProperty, processProperty + "." + domain.key);
    loggerNames.forEach(loggerName -> {
      Logger logger = getRootContext().getLogger(loggerName);
      logger.setLevel(newLevel);
    });
    return newLevel;
  }

  /**
   * Configure the log level of the specified logger to specified level.
   * <p>
   * Any level is allowed.
   * </p>
   */
  public Logger configureLogger(String loggerName, Level level) {
    Logger logger = getRootContext().getLogger(loggerName);
    logger.setLevel(level);
    return logger;
  }

  public Level getLoggerLevel(String loggerName) {
    return getRootContext().getLogger(loggerName).getLevel();
  }

  /**
   * Generally used to reset logback in logging tests
   */
  public void resetFromXml(String xmlResourcePath) throws JoranException {
    LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
    JoranConfigurator configurator = new JoranConfigurator();
    configurator.setContext(context);
    context.reset();
    configurator.doConfigure(LogbackHelper.class.getResource(xmlResourcePath));
  }

  public RollingPolicy createRollingPolicy(Context ctx, Props props, String filenamePrefix) {
    String rollingPolicy = props.value(ROLLING_POLICY_PROPERTY, "time:yyyy-MM-dd");
    int maxFiles = props.valueAsInt(MAX_FILES_PROPERTY, 7);
    File logsDir = props.nonNullValueAsFile(ProcessProperties.PATH_LOGS);

    if (rollingPolicy.startsWith("time:")) {
      return new TimeRollingPolicy(ctx, filenamePrefix, logsDir, maxFiles, StringUtils.substringAfter(rollingPolicy, "time:"));

    } else if (rollingPolicy.startsWith("size:")) {
      return new SizeRollingPolicy(ctx, filenamePrefix, logsDir, maxFiles, StringUtils.substringAfter(rollingPolicy, "size:"));

    } else if ("none".equals(rollingPolicy)) {
      return new NoRollingPolicy(ctx, filenamePrefix, logsDir, maxFiles);

    } else {
      throw new MessageException(format("Unsupported value for property %s: %s", ROLLING_POLICY_PROPERTY, rollingPolicy));
    }
  }

  public abstract static class RollingPolicy {
    protected final Context context;
    final String filenamePrefix;
    final File logsDir;
    final int maxFiles;

    RollingPolicy(Context context, String filenamePrefix, File logsDir, int maxFiles) {
      this.context = context;
      this.filenamePrefix = filenamePrefix;
      this.logsDir = logsDir;
      this.maxFiles = maxFiles;
    }

    public abstract FileAppender<ILoggingEvent> createAppender(String appenderName);
  }

  /**
   * Log files are not rotated, for example for unix command logrotate is in place.
   */
  private static class NoRollingPolicy extends RollingPolicy {
    private NoRollingPolicy(Context context, String filenamePrefix, File logsDir, int maxFiles) {
      super(context, filenamePrefix, logsDir, maxFiles);
    }

    @Override
    public FileAppender<ILoggingEvent> createAppender(String appenderName) {
      FileAppender<ILoggingEvent> appender = new FileAppender<>();
      appender.setContext(context);
      appender.setFile(new File(logsDir, filenamePrefix + ".log").getAbsolutePath());
      appender.setName(appenderName);
      return appender;
    }
  }

  /**
   * Log files are rotated according to time (one file per day, month or year).
   * See http://logback.qos.ch/manual/appenders.html#TimeBasedRollingPolicy
   */
  private static class TimeRollingPolicy extends RollingPolicy {
    private final String datePattern;

    private TimeRollingPolicy(Context context, String filenamePrefix, File logsDir, int maxFiles, String datePattern) {
      super(context, filenamePrefix, logsDir, maxFiles);
      this.datePattern = datePattern;
    }

    @Override
    public FileAppender<ILoggingEvent> createAppender(String appenderName) {
      RollingFileAppender<ILoggingEvent> appender = new RollingFileAppender<>();
      appender.setContext(context);
      appender.setName(appenderName);
      String filePath = new File(logsDir, filenamePrefix + ".log").getAbsolutePath();
      appender.setFile(filePath);

      TimeBasedRollingPolicy rollingPolicy = new TimeBasedRollingPolicy();
      rollingPolicy.setContext(context);
      rollingPolicy.setFileNamePattern(StringUtils.replace(filePath, filenamePrefix + ".log", filenamePrefix + ".%d{" + datePattern + "}.log"));
      rollingPolicy.setMaxHistory(maxFiles);
      rollingPolicy.setParent(appender);
      rollingPolicy.start();
      appender.setRollingPolicy(rollingPolicy);

      return appender;
    }
  }

  /**
   * Log files are rotated according to their size.
   * See http://logback.qos.ch/manual/appenders.html#FixedWindowRollingPolicy
   */
  private static class SizeRollingPolicy extends RollingPolicy {
    private final String size;

    private SizeRollingPolicy(Context context, String filenamePrefix, File logsDir, int maxFiles, String parameter) {
      super(context, filenamePrefix, logsDir, maxFiles);
      this.size = parameter;
    }

    @Override
    public FileAppender<ILoggingEvent> createAppender(String appenderName) {
      RollingFileAppender<ILoggingEvent> appender = new RollingFileAppender<>();
      appender.setContext(context);
      appender.setName(appenderName);
      String filePath = new File(logsDir, filenamePrefix + ".log").getAbsolutePath();
      appender.setFile(filePath);

      SizeBasedTriggeringPolicy<ILoggingEvent> trigger = new SizeBasedTriggeringPolicy<>(size);
      trigger.setContext(context);
      trigger.start();
      appender.setTriggeringPolicy(trigger);

      FixedWindowRollingPolicy rollingPolicy = new FixedWindowRollingPolicy();
      rollingPolicy.setContext(context);
      rollingPolicy.setFileNamePattern(StringUtils.replace(filePath, filenamePrefix + ".log", filenamePrefix + ".%i.log"));
      rollingPolicy.setMinIndex(1);
      rollingPolicy.setMaxIndex(maxFiles);
      rollingPolicy.setParent(appender);
      rollingPolicy.start();
      appender.setRollingPolicy(rollingPolicy);

      return appender;
    }
  }

  public enum LogDomain {
    SQL("sql"),
    ES_CLIENT("es"),
    JMX("jmx");

    private final String key;

    LogDomain(String key) {
      this.key = key;
    }
  }
}
