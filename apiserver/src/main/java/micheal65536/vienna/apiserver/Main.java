package micheal65536.vienna.apiserver;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;
import org.apache.commons.cli.*;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.config.Configurator;
import org.jetbrains.annotations.NotNull;

import micheal65536.vienna.apiserver.routes.AuthenticatedRouter;
import micheal65536.vienna.apiserver.routes.ResourcePacksRouter;
import micheal65536.vienna.apiserver.routes.SigninRouter;
import micheal65536.vienna.apiserver.routing.Application;
import micheal65536.vienna.apiserver.routing.Router;
import micheal65536.vienna.apiserver.utils.BuildplateInstanceRequestHandler;
import micheal65536.vienna.apiserver.utils.BuildplateInstancesManager;
import micheal65536.vienna.db.DatabaseException;
import micheal65536.vienna.db.EarthDB;
import micheal65536.vienna.eventbus.client.EventBusClient;
import micheal65536.vienna.eventbus.client.EventBusClientException;
import micheal65536.vienna.objectstore.client.ObjectStoreClient;
import micheal65536.vienna.objectstore.client.ObjectStoreClientException;
import micheal65536.vienna.staticdata.StaticData;
import micheal65536.vienna.staticdata.StaticDataException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

public class Main
{
    public static void main(String[] args)
    {
        Configurator.setRootLevel(Level.DEBUG);

        Options options = buildOptions();

        CommandLine commandLine;
        int httpPort;
        String dbConnectionString;
        String staticDataPath;
        String eventBusConnectionString;
        String objectStoreConnectionString;

        try
        {
            commandLine = new DefaultParser().parse(options, args);

            if (args.length == 0 || commandLine.hasOption("help"))
            {
                printHelp(options);
                return;
            }

            httpPort = commandLine.hasOption("port")
                    ? (int) (long) commandLine.getParsedOptionValue("port")
                    : 8080;

            dbConnectionString = commandLine.hasOption("db")
                    ? commandLine.getOptionValue("db")
                    : "./earth.db";

            staticDataPath = commandLine.hasOption("staticData")
                    ? commandLine.getOptionValue("staticData")
                    : "./data";

            eventBusConnectionString = commandLine.hasOption("eventbus")
                    ? commandLine.getOptionValue("eventbus")
                    : "localhost:5532";

            objectStoreConnectionString = commandLine.hasOption("objectstore")
                    ? commandLine.getOptionValue("objectstore")
                    : "localhost:5396";
        }
        catch (ParseException exception)
        {
            LogManager.getLogger().error("Invalid arguments: " + exception.getMessage());
            printHelp(options);
            return;
        }

        LogManager.getLogger().info("Loading static data");
        StaticData staticData;
        try
        {
            staticData = new StaticData(new File(staticDataPath));
        }
        catch (StaticDataException exception)
        {
            LogManager.getLogger().fatal("Failed to load static data", exception);
            return;
        }
        LogManager.getLogger().info("Loaded static data");

        LogManager.getLogger().info("Connecting to database");
        EarthDB earthDB;
        try
        {
            earthDB = EarthDB.open(dbConnectionString);
        }
        catch (DatabaseException exception)
        {
            LogManager.getLogger().fatal("Could not connect to database", exception);
            return;
        }
        LogManager.getLogger().info("Connected to database");

        LogManager.getLogger().info("Connecting to event bus");
        EventBusClient eventBusClient;
        try
        {
            eventBusClient = EventBusClient.create(eventBusConnectionString);
        }
        catch (EventBusClientException exception)
        {
            LogManager.getLogger().fatal("Could not connect to event bus", exception);
            return;
        }
        LogManager.getLogger().info("Connected to event bus");

        LogManager.getLogger().info("Connecting to object storage");
        ObjectStoreClient objectStoreClient;
        try
        {
            objectStoreClient = ObjectStoreClient.create(objectStoreConnectionString);
        }
        catch (ObjectStoreClientException exception)
        {
            LogManager.getLogger().fatal("Could not connect to object storage", exception);
            return;
        }
        LogManager.getLogger().info("Connected to object storage");

        Application application = buildApplication(earthDB, staticData, eventBusClient, objectStoreClient);

        startServer(httpPort, application);
    }

    private static Options buildOptions()
    {
        Options options = new Options();

        options.addOption(Option.builder()
                .longOpt("help")
                .desc("Show this help message")
                .build());

        options.addOption(Option.builder()
                .longOpt("port")
                .hasArg()
                .argName("port")
                .type(Number.class)
                .desc("Port to listen on (default: 8080)")
                .build());

        options.addOption(Option.builder()
                .longOpt("db")
                .hasArg()
                .argName("path")
                .desc("Path to database file (default: ./earth.db)")
                .build());

        options.addOption(Option.builder()
                .longOpt("staticData")
                .hasArg()
                .argName("dir")
                .desc("Path to static data directory (default: ./data)")
                .build());

        options.addOption(Option.builder()
                .longOpt("eventbus")
                .hasArg()
                .argName("host:port")
                .desc("Event bus address (default: localhost:5532)")
                .build());

        options.addOption(Option.builder()
                .longOpt("objectstore")
                .hasArg()
                .argName("host:port")
                .desc("Object storage address (default: localhost:5396)")
                .build());

        return options;
    }

    private static void printHelp(Options options)
    {
        HelpFormatter formatter = new HelpFormatter();

        String header = "\nVienna API Server\n\nOptions:\n";

        String footer = "\nExamples:\n" +
                "  java -jar apiserver.jar\n" +
                "  java -jar apiserver.jar --port 9090\n" +
                "  java -jar apiserver.jar --db ./earth.db --staticData ./data\n" +
                "  java -jar apiserver.jar --eventbus localhost:5532 --objectstore localhost:5396\n";

        formatter.printHelp(
                "java -jar apiserver.jar [options]",
                header,
                options,
                footer,
                true
        );
    }

    @NotNull
    private static Application buildApplication(@NotNull EarthDB earthDB, @NotNull StaticData staticData, @NotNull EventBusClient eventBusClient, @NotNull ObjectStoreClient objectStoreClient)
    {
        Application application = new Application();
        Router router = new Router();
        application.router.addSubRouter("/*", 0, router);

        BuildplateInstancesManager buildplateInstancesManager = new BuildplateInstancesManager(eventBusClient);

        router.addSubRouter("/auth/api/v1.1/*", 3, new SigninRouter());
        router.addSubRouter("/auth/api/v1.1/*", 3, new AuthenticatedRouter(earthDB, staticData, eventBusClient, objectStoreClient, buildplateInstancesManager));
        router.addSubRouter("/api/v1.1/*", 2, new SigninRouter());
        router.addSubRouter("/api/v1.1/*", 2, new ResourcePacksRouter());

        BuildplateInstanceRequestHandler.start(earthDB, eventBusClient, objectStoreClient, buildplateInstancesManager, staticData.catalog);

        return application;
    }

    private static void startServer(int port, @NotNull Application application)
    {
        LogManager.getLogger().info("Starting embedded Tomcat server");

        File tomcatDir;
        try
        {
            tomcatDir = Files.createTempDirectory("vienna-apiserver-tomcat-").toFile();
        }
        catch (IOException exception)
        {
            LogManager.getLogger().fatal("Could not start Tomcat server", exception);
            return;
        }

        File baseDir = new File(tomcatDir, "baseDir");
        baseDir.mkdir();
        File docBase = new File(tomcatDir, "docBase");
        docBase.mkdir();

        Tomcat tomcat = new Tomcat();
        tomcat.setBaseDir(baseDir.getAbsolutePath());

        Connector connector = new Connector();
        connector.setPort(port);
        tomcat.setConnector(connector);

        Context context = tomcat.addContext("", docBase.getAbsolutePath());

        tomcat.addServlet("", "", new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                application.handleRequest(request, response);
            }
        });

        context.addServletMappingDecoded("/*", "");

        try
        {
            tomcat.start();
        }
        catch (LifecycleException exception)
        {
            LogManager.getLogger().fatal("Could not start Tomcat server", exception);
            return;
        }

        LogManager.getLogger().info("Server started on port " + port);
    }
}
