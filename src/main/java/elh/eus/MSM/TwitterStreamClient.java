/*
 * Copyright 2014 Iñaki San Vicente

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
 */

package elh.eus.MSM;

import com.google.common.collect.Lists;
import com.twitter.hbc.ClientBuilder;
import com.twitter.hbc.core.Client;
import com.twitter.hbc.core.Constants;
import com.twitter.hbc.core.Hosts;
import com.twitter.hbc.core.HttpHosts;
import com.twitter.hbc.core.endpoint.StatusesFilterEndpoint;
import com.twitter.hbc.core.endpoint.StatusesSampleEndpoint;
import com.twitter.hbc.core.event.Event;
import com.twitter.hbc.core.processor.StringDelimitedProcessor;
import com.twitter.hbc.httpclient.BasicClient;
import com.twitter.hbc.httpclient.auth.Authentication;
import com.twitter.hbc.httpclient.auth.OAuth1;
import com.twitter.hbc.twitter4j.Twitter4jStatusClient;
import com.twitter.hbc.twitter4j.handler.StatusStreamHandler;
import com.twitter.hbc.twitter4j.message.DisconnectMessage;
import com.twitter.hbc.twitter4j.message.StallWarningMessage;

import twitter4j.StallWarning;
import twitter4j.Status;
import twitter4j.StatusDeletionNotice;
import twitter4j.StatusListener;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;


public class TwitterStreamClient {

	private Properties params = new Properties();	
	private String store = "";
		
	public String getStore() {
		return store;
	}

	public void setStore(String store) {
		this.store = store;
	}

	StatusListener listener1 = new StatusListener() {
        @Override
        public void onStatus(Status status) 
        {
        	switch (getStore()) // print the message to stdout
			{
			case "stout": System.out.println(status.toString()); break;
			case "db": System.out.println("db - @" + status.getUser().getScreenName() + " - " + status.getText()); break;
			case "solr": System.out.println("solr - @" + status.getUser().getScreenName() + " - " + status.getText()); break;
			} 
        }

        @Override
        public void onDeletionNotice(StatusDeletionNotice statusDeletionNotice) {
            System.out.println("Got a status deletion notice id:" + statusDeletionNotice.getStatusId());
        }

        @Override
        public void onTrackLimitationNotice(int numberOfLimitedStatuses) {
            System.out.println("Got track limitation notice:" + numberOfLimitedStatuses);
        }

        @Override
        public void onScrubGeo(long userId, long upToStatusId) {
            System.out.println("Got scrub_geo event userId:" + userId + " upToStatusId:" + upToStatusId);
        }

        @Override
        public void onStallWarning(StallWarning warning) {
            System.out.println("Got stall warning:" + warning);
        }

        @Override
        public void onException(Exception ex) {
            ex.printStackTrace();
        }        
        
    };
    
	// A bare bones StatusStreamHandler, which extends listener and gives some extra functionality
	private StatusListener listener2 = new StatusStreamHandler() {
		@Override
		public void onStatus(Status status) {}

		@Override
		public void onDeletionNotice(StatusDeletionNotice statusDeletionNotice) {}

		@Override
		public void onTrackLimitationNotice(int limit) {}

		@Override
		public void onScrubGeo(long user, long upToStatus) {}

		@Override
		public void onStallWarning(StallWarning warning) {}

		@Override
		public void onException(Exception e) {}

		@Override
		public void onDisconnectMessage(DisconnectMessage message) {}

		@Override
		public void onStallWarningMessage(StallWarningMessage warning) {}

		@Override
		public void onUnknownMessageType(String s) {}
	};

	/**
	 * 
	 * Constructor and main function. Starts a connections and gives the messages to the corresponding handlers.
	 * 
	 * @param config
	 * @param store
	 */
	public TwitterStreamClient (String config, String store)
	{
		try {
			params.load(new FileInputStream(new File(config)));
		} catch (FileNotFoundException fe){
			System.err.println("elh-MSM::TwitterStreamClient - Config file not found "+config);
			System.exit(1);
		} catch (IOException ioe){
			System.err.println("elh-MSM::TwitterStreamClient - Config file could not read "+config);
			System.exit(1);
		} 
		
		setStore(store);
		
		/** Set up your blocking queues: Be sure to size these properly based on expected TPS of your stream */
		BlockingQueue<String> msgQueue = new LinkedBlockingQueue<String>(100000);
		BlockingQueue<Event> eventQueue = new LinkedBlockingQueue<Event>(1000);

		/** Declare the host you want to connect to, the endpoint, and authentication (basic auth or oauth) */
		Hosts hosebirdHosts = new HttpHosts(Constants.STREAM_HOST);
		StatusesFilterEndpoint hosebirdEndpoint = new StatusesFilterEndpoint();
		// Optional: set up some followings and track terms
		//List<Long> followings = Lists.newArrayList(1234L, 566788L);
		String searchTerms = params.getProperty("searchTerms", "twitter,api");		
		List<String> terms = Arrays.asList(searchTerms.split(","));
		//hosebirdEndpoint.followings(followings);
		hosebirdEndpoint.trackTerms(terms);
		
		try {
			// These secrets should be read from a config file		
			String ckey = params.getProperty("consumerKey");
			String csecret = params.getProperty("consumerSecret");
			String token = params.getProperty("accessToken");
			String tsecret = params.getProperty("accesTokenSecret");
			//System.err.println("twitter data:"+ckey+" "+csecret+" "+token+" "+tsecret);	
			
			Authentication hosebirdAuth = new OAuth1(ckey, csecret, token, tsecret);
		
			//create the client
			ClientBuilder builder = new ClientBuilder()
			.hosts(hosebirdHosts)
			.authentication(hosebirdAuth)
			.endpoint(hosebirdEndpoint)
			.processor(new StringDelimitedProcessor(msgQueue))
			.eventMessageQueue(eventQueue);

			Client client = builder.build();

			// Create an executor service which will spawn threads to do the actual work of parsing the incoming messages and
			// calling the listeners on each message
			int numProcessingThreads = 4;
			ExecutorService service = Executors.newFixedThreadPool(numProcessingThreads);

			// Wrap our BasicClient with the twitter4j client
			Twitter4jStatusClient t4jClient = new Twitter4jStatusClient(
					client, msgQueue, Lists.newArrayList(listener1, listener2), service);

			// Establish a connection
			t4jClient.connect();
			for (int threads = 0; threads < numProcessingThreads; threads++) {
				// This must be called once per processing thread
				t4jClient.process();
			}

		} catch (Exception e) {
			System.err.println("elh-MSM::TwitterStreamClient - Authentication problem");			
			e.printStackTrace();
			System.exit(1);
		}

		/*while (!client.isDone()) 
		{
			String message = "";
			try {
                message = msgQueue.take();
                processMsg(message);
            } catch (InterruptedException e) {                
                e.printStackTrace();              
            }			
			switch (store) // print the message to stdout
			{
			case "stout": System.out.println(message); break;
			case "db": System.out.println(message); break;
			case "solr": System.out.println(message); break;
			} 
		 }*/
		
		//client.stop();
	
		//After we have created a Client, we can connect and process messages:
		//client.connect();

	}
	
	/*sub store_tweet_toDB
	{
	    my $status = shift;

	    my $time = `date`;
	    print STDERR "\ntweet captured - $time - ";

	    foreach $i (%{$status})
	    {
	        print STDERR "$i\t";
	    } 
	    
	    my $langTwitter = $status->{lang};
	    my $text = $status->{text};
	    $text=~s/\n/ /g;
	    print STDERR "\ntweet: $text - ";
	    my $utf=encode_utf8($text);   
	    
	    print STDERR "\n\nTWEET UTF8: $utf - \n\n";

	    my $langId = &langDetect($lang, $langTwitter, $utf);

	    # mention language is accepted
	    if ("$langId" ne "unk")
	    {
		print STDERR " correct lang! store mention to DB if any keyword matches  - $lang - $langId -\n";
		
		# open DB connection
		my $kon=DBI->connect("DBI:mysql:DSS2016_MoodMap_DB:localhost","Ireom","ireom862admin");
		$kon->do(qq{SET NAMES 'utf8';});

		# get the max id up until now
		my $sth = $kon->prepare("SELECT max(id) FROM mention");
		$sth->execute();
		my @results= $sth->fetchrow_array();
		my $id=$results[0];      
		$id++;
		$sth->finish;

		print STDERR " id for the next mention: $id\n";

		# Extract desired fields from twitter status 
		my $tweetId=$status->{id};
		my $author=$status->{user}{screen_name};
		my $date=$status->{created_at};
		#Standarize date format
		my @dateFields=split /\s+/, $date;
		$date=$dateFields[5]."-".$dateFields[1]."-".$dateFields[2]." ".$dateFields[3]." ".$dateFields[4];
		#months to numeric values
		$date=~s/Jan/01/;
		$date=~s/Feb/02/;
		$date=~s/Mar/03/;
		$date=~s/Apr/04/;
		$date=~s/May/05/;
		$date=~s/Jun/06/;
		$date=~s/Jul/07/;
		$date=~s/Aug/08/;
		$date=~s/Sep/09/;
		$date=~s/Oct/10/;
		$date=~s/Nov/11/;
		$date=~s/Dec/12/;
		my $url="https://twitter.com/".$status->{id_str}."/status/".$tweetId;   

		# prepare the sql statements to insert the mention in the DB and insert.
		my $sth_mention=$kon->prepare("insert ignore into mention (id, date, source_id, url, text, lang, polarity) values (?,?,?,?,?,?,NULL)") || die "Couldn't prepare: " . $sth_mention->errstr;
		#my $sth_keyword=$kon->prepare("insert ignore into keyword (term) values (?)") || die "Couldn't prepare: " . $sth_keyword->errstr;	
		my $sth_keywordMention=$kon->prepare("insert ignore into keyword_mention (mention_id, keyword_term, keyword_lang) values (?,?,?)") || die "Couldn't prepare: " . $sth_keywordMention->errstr;
		#my $sth_userkeyword=$kon->prepare("insert ignore into user_keyword (user_nickname, user_pass, keyword_term) values (?,?,?)") || die "Couldn't prepare: " . $sth_userKeyword->errstr;
		my $sth_source=$kon->prepare("insert ignore into source (id, type, influence) values (?,?,NULL)") || die "Couldn't prepare: " . $sth_source->errstr;


		#insert keywords into DB -- 
		# PROBLEM!!! -> keywords are not always in the tweet. What to do in such cases?
		#               - For the time being if no keyword is found discard the mention
		my $utflc=lc($utf);
		$utflc=" ".$utflc." ";
		my @keysToStore;
		for my $key (@keywords)
		{
		    $key=~s/^\s+//;
		    $key=~s/\s+$//;
		    $keylc=lc($key);
		    # insert keyword to db (no matter if the keyword is not used here)
		    #$sth_keyword->execute($keylc) || die "Couldn't execute statement: " . $sth_keyword->errstr;
		    # keyword must be a whole word (#key, @key or ' key ') shall much, except for basque tweets where suffixes are admitted.
		    if ($utflc=~/[#@\s]$keylc[\s\!-\(\[\)\]\?\.\,\;\:]/)
		    {
			push(@keysToStore,$key);       	
		    }
		    elsif ($langId eq "eu")
		    {
			if ($utflc=~/[#@\s]$keylc/)
			{
			    push(@keysToStore,$key);       	
			}	
		    }
		}
		
		# store the mention in the DB unless no keyword is found to link the mention with.
		if (($#keysToStore) < 0)
		{
		    print STDERR " lang is correct but no keyword could be found, tweet discarded! \n";
		}
		else
		{
		    # insert the mention into DB	    
		    $sth_mention->execute($id, $date, $author, $url, $utf, $langId) || die "Couldn't execute statement: " . $sth_mention->errstr;
		    $sth_mention->finish;
		    
		    for my $k (@keysToStore)
		    {
			$sth_keywordMention->execute($id, $k, $langId) || die "Couldn't execute statement: " . $sth_keywordMention->errstr;
			#$sth_userkeyword->execute($user, $pass, $k) || die "Couldn't execute statement: " . $sth_userkeyword->errstr;
			$sth_source->execute($author,'Twitter') || die "Couldn't execute statement: " . $sth_source->errstr;
		    }
		}       
		$sth_keywordMention->finish;
		#$sth_userkeyword->finish;
		$sth_source->finish;

		$kon->disconnect;
	    }
	    else
	    {
		print STDERR " other lang! - $lang - $langId - \n";
		#return 2;
	    }
	    #print OUT "$langId";
	}*/
	    
	
}

