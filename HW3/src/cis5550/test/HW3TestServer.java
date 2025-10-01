package cis5550.test;

import java.nio.file.*;
import java.io.*;

import static cis5550.webserver.Server.*;
import cis5550.webserver.Session;

public class HW3TestServer {
	public static void main(String args[]) throws Exception {
	    //port(8080);
	    securePort(443);
	    get("/", (req, res) -> "Hello World - this is Vivian Chiang!");

	    start();
	}
}
