package Aggregator;

import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dtu.is31380.Communication.commexercise.pubsub.PubSubServer;
import dtu.is31380.Communication.commexercise.pubsub.PubSubServerImpl;
import dtu.is31380.Communication.commexercise.pubsub.PubSubSubscriberListener;
import dtu.is31380.Communication.commexercise.rpc.CallListener;
import dtu.is31380.Communication.commexercise.rpc.RpcClient;
import dtu.is31380.Communication.commexercise.rpc.RpcClientImpl;
import dtu.is31380.Communication.commexercise.rpc.RpcServer;
import dtu.is31380.Communication.commexercise.rpc.RpcServerImpl;

public class Aggregator {

    private long initTime = 0;

    private RpcClient grid_client = null;
    private static String GRID_ADDRESS = "localhost";
    public static int GRID_PORT = Grid.GRID_PORT;
    public static final String FLEXIBILITY_ALL = "FlexAll";

    private RpcServer grid_server = null;
    public static int AGG_GRID_PORT = 8080;
    public static final String FUN_TIME_SYNC = Grid.FUN_TIME_SYNC; //keep it short
    private static final String FUN_REQUEST = Grid.FUN_REQUEST; //keep it short
    private static final String FUN_ACTIVATION = Grid.FUN_ACTIVATION; //keep it short


    private RpcServer house_server = null;
    public static int AGG_HOUSE_PORT = 8082;

    private PubSubServer pubSubServer;
    public static int AGG_PORT_PUB = 9090;
    public static final String TOPIC = "Flexibility";
    public static final String FLEXIBILITY_ALL_AT_T0 = "FlexT0";
    public static final String ACTIVATE = "Activate";
    public static final String RESET = "Reset";

    public class Record implements Comparable < Record > {
        public String homeName = null;
        public double flexibility_time = 0;
        public double flexibility_power = 0;
        Record(String homeName, double flexibility_time, Double flexibility_power) {
            this.homeName = homeName;
            this.flexibility_time = flexibility_time;
            this.flexibility_power = flexibility_power;
        }
        public String toString() {
            return homeName + " : " + flexibility_time + " : " + flexibility_power;
        }
        public int compareTo(Record anotherInstance) {
            return (int)(this.flexibility_power - anotherInstance.flexibility_power);
        }
    }
    private class Records {
        public List < Record > list = null;
        public boolean valid = true;
        Records() {
            list = new ArrayList < Record > ();
        }
    }
    private Records records = null;

    private void wait_random(long ms) {
        try {
            Thread.sleep((long)(ms + ms * Math.random()));
        } catch (InterruptedException e1) {};
    }
    private void exc(String s, long ms) {
        System.out.println(s);
        wait_random(ms);
    }
    private void exc(String s) {
        exc(s + " Trying again in random time of 1-2s...", 1000);
    }

    	private static final Logger log = LoggerFactory.getLogger(Aggregator.class);

    private Aggregator() {
        records = new Records();
        // create an rpc server listening on port
        for (boolean i = true; i;) try {
            grid_server = new RpcServerImpl(AGG_GRID_PORT).start();
            house_server = new RpcServerImpl(AGG_HOUSE_PORT).start();
            i = false;
        } catch (Exception e) {
            exc("Setting calls listenner for house or grid exception!");
        }
        System.out.println("Calls listenners set");

        for (boolean i = true; i;) try {
            grid_client = new RpcClientImpl("http://" + GRID_ADDRESS + ":" + GRID_PORT);
            i = false;
        } catch (MalformedURLException e) {
            exc("Connection to grid refused! Check address and port.");
        }
        System.out.println("Connected to grid.");

        
        // create a pubsub server
        //log.info("Starting PubSub Server");
        for (int i = 1; i <= 10; i++) try {
            pubSubServer = new PubSubServerImpl(AGG_PORT_PUB).start();
            i = 10;
        } catch (Exception e) {
            exc(i + ". Publisher start exception");
        }
        System.out.println("Publisher server started.");
        
        System.out.println("Asking grid for InitTime.");
        String[] reply = null;
        for (boolean i=true;i;) try {
            reply = grid_client.callSync(FUN_TIME_SYNC, new String[] {});
            if (reply.length == 1) {
                initTime = Long.valueOf(reply[0]);
                System.out.println("InitTime set to " + initTime + ".");
                System.out.println("Sending initTime to houses");
                pubSubServer.send(TOPIC, new String[] {
                    FUN_TIME_SYNC, String.valueOf(initTime)
                });
                System.out.println("Time published.");
            } else {
                System.out.println("Wrong number of arguments for function " + FUN_TIME_SYNC +"!");
            }
            i = false;
        } catch (Exception e) {
            exc("Time sync call exception! Possibly Grid is down or acknowlege message was dropped!");
        }

        // add subscriber listener (gets called when a client subscribes or unsubscribes)
        pubSubServer.addSubscriberListener(new PubSubSubscriberListener() {
            public void subscriberJoined(String topic, String id) {
                System.out.println("Subscriber '" + id + "' joined for topic '" + topic + ". Sending the InitTime.");
                pubSubServer.send(TOPIC, new String[] {
                    FUN_TIME_SYNC, String.valueOf(initTime)
                }); // sends time each time smb join
            }
            public void subscriberLeft(String topic, String id) {
                System.out.println("Subscriber '" + id + "' left for topic '" + topic + ".");
            }
        });

        // add a call listener that will get called when a gird does an RPC call to the aggregator
        grid_server.setCallListener(new CallListener() {@
            Override
            public String[] receivedSyncCall(String
                function, String[] args) throws Exception {
                System.out.println("grid>" + function +"(" + Arrays.toString(args) + ")");

                switch (function) {
                    case FUN_TIME_SYNC:
                        if (args.length == 1) {
                            initTime = Long.valueOf(args[0]);
                            System.out.println("InitTime set to " + initTime + ".");
                            System.out.println("Sending initTime to houses");
                            String[] reply = null;
                            // do synchronous call to agg TIME SYNCH
                            pubSubServer.send(TOPIC, new String[] {
                                FUN_TIME_SYNC, String.valueOf(initTime)
                            });
                            System.out.println("Time published.");
                            return new String[] {
                                "ACC"
                            };
                        } else {
                            System.out.println("Wrong number of arguments for function " + function +"!");
                            return new String[] {
                                "EXC"
                            };
                        }
                    case FUN_REQUEST:
                        if (args.length == 5) {
                            String[] reply = null;
                            //  		        	startTime,endTime,maxDelay,minDuration,regType

                            System.out.println("Forwarding request to houses.");
                            pubSubServer.send(TOPIC, new String[] {
                                FLEXIBILITY_ALL_AT_T0, args[4], args[0], args[1]
                            });

                            records.list.clear();
                            System.out.println("Waiting one second for flexibility informations from houses.");
                            Thread.sleep(1000);
                            System.out.println(records.list.size() + " houses responded.");
                            double delta_power = 0;
                            double bufor_t = 0;
                            double bufor_p = 10000000;

                            Collections.sort(records.list);
                            System.out.println("Home name : flexibility time : flexibility power");
                            for (Record record: records.list) {
                                if (record.flexibility_time > Double.valueOf(args[3])) delta_power += record.flexibility_power;
                                else {
                                    bufor_t += record.flexibility_time;
                                    bufor_p = Math.min(bufor_p, record.flexibility_power);
                                    if (record.flexibility_time + bufor_t > Double.valueOf(args[3])) {
                                        delta_power += bufor_p;
                                        bufor_t = 0;
                                        bufor_p = 100000000;
                                    }
                                }
                                System.out.println(record.toString());
                            }

                            return new String[] {
                                String.valueOf(delta_power)
                            };
                        } else {
                            System.out.println("Wrong number of arguments!");
                            return new String[] {
                                "EXC"
                            };
                        }
                    case FUN_ACTIVATION:
                        pubSubServer.send(TOPIC, new String[] {
                            ACTIVATE, args[0]
                        }); //to be change for sequenctional calls
                        return new String[] {
                            "ACC"
                        };
                    default:
                        return new String[] {
                            "Function '",
                            function, "' does not exist."
                        };
                }
            }@
            Override
            public String[] receivedAsyncCall(String
                function, String[] args, long callID) throws Exception {
                return null; // not implemented for this test
            }
        });

        house_server.setCallListener(new CallListener() {@
            Override
            public String[] receivedSyncCall(String
                function, String[] args) throws Exception {
                System.out.println("house>" + function +"(" + Arrays.toString(args) + ")");
                switch (function) {
                    case FUN_TIME_SYNC:
                        return new String[] {
                            "ACC"
                        };
                    case FLEXIBILITY_ALL_AT_T0:
                        if (args.length == 3) {
                            //homeName,flexibility_time,flexibility_power
                            Record current = new Record(args[0], Double.valueOf(args[1]), Double.valueOf(args[2]));
                            records.list.add(current);
                            return new String[] {
                                "ACC"
                            };
                        }
                    case ACTIVATE:
                        return new String[] {
                            "ACC"
                        };
                    default:
                        return new String[] {
                            "EXC"
                        };
                }
            }@
            Override
            public String[] receivedAsyncCall(String
                function, String[] args, long callID) throws Exception {
                return null; // not implemented for this test
            }
        });
    }

    public static void main(String args[]) {
        switch (args.length) {
            case 5:
                AGG_PORT_PUB = Integer.valueOf(args[4]);
                System.out.println("Assigned aggregator publish server to port: " + AGG_PORT_PUB);
            case 4:
                AGG_HOUSE_PORT = Integer.valueOf(args[3]);
                System.out.println("Assigned aggregator port for communication with houses to: " + AGG_HOUSE_PORT);
            case 3:
                AGG_GRID_PORT = Integer.valueOf(args[2]);
                System.out.println("Assigned aggregator port for communication with grid to: " + AGG_GRID_PORT);
            case 2:
                GRID_ADDRESS = args[1];
                System.out.println("Assigned grid address: " + GRID_ADDRESS);
            case 1:
                GRID_PORT = Integer.valueOf(args[0]);
                System.out.println("Assigned grid port: " + GRID_PORT);
                break;
            default:
                System.out.println("Used default initial values.");
        }
        new Aggregator();
    }

}