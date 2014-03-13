package org.jenkinsci.test.acceptance.controller;

import org.jclouds.compute.domain.NodeMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.EmptyStackException;
import java.util.Random;
import java.util.Stack;

/**
 * @author Vivek Pandey
 */
public class JcloudsMachine implements Machine {
    private final NodeMetadata nodeMetadata;
    private final JcloudsMachineProvider machineProvider;

    public static final int BEGINNING_PORT = 20000;

    private final Stack<Integer> availablePorts = new Stack<>();

    private final String dir;

    public JcloudsMachine(JcloudsMachineProvider machineProvider, NodeMetadata nodeMetadata) {
        this.nodeMetadata = nodeMetadata;
        this.machineProvider = machineProvider;
        for(int port:machineProvider.getAvailableInboundPorts()){
            availablePorts.push(port);
        }

        this.dir = "./machine_home_"+newDirSuffix();
        Ssh ssh = connect();
        ssh.executeRemoteCommand("mkdir -p "+this.dir);
    }

    @Override
    public Ssh connect() {
        try {
            Ssh ssh = new Ssh(getUser(),getPublicIpAddress());
            machineProvider.authenticator().authenticate(ssh.getConnection());
            return ssh;
        } catch (IOException e) {
            throw new AssertionError("Failed to create ssh connection",e);
        }
    }

    @Override
    public String getPublicIpAddress(){
        return nodeMetadata.getPublicAddresses().iterator().next();
    }

    @Override
    public String getUser(){
        return (nodeMetadata.getCredentials() == null) ? "ubuntu" : nodeMetadata.getCredentials().getUser();
    }

    @Override
    public String dir() {
        return dir;
    }

    @Override
    public int getNextAvailablePort(){
        try{
            return availablePorts.pop();
        }catch (EmptyStackException e){
            throw new AssertionError("No more free inbound ports",e);
        }
    }

    @Override
    public void close() throws IOException {
        logger.info("Destroying node: " + nodeMetadata);
        machineProvider.destroy(nodeMetadata.getId());
    }

    @Override
    public void reset(){
        logger.info("Resetting node: "+nodeMetadata);
        Ssh ssh = connect();
        ssh.executeRemoteCommand("rm -rd machine*");
        try{
            ssh.executeRemoteCommand("killall java");
        }catch (Exception e){
            //ignore errors, if no java process is running, it gives error
            logger.error("Failed to kill java processes: "+e.getMessage());
        }
    }


    public static long newDirSuffix(){
        SecureRandom secureRandom = new SecureRandom();
        long secureInitializer = secureRandom.nextLong();
        return Math.abs(new Random( secureInitializer + Runtime.getRuntime().freeMemory()).nextInt());
    }

    private static final Logger logger = LoggerFactory.getLogger(JcloudsMachine.class);


}