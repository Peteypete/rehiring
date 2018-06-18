# re-hiring

My Javascript/mxWeb&trade; ["AskHN: Who Is Hiring"](https://github.com/kennytilton/whoshiring) browser ported to CLJS/[re-frame](https://github.com/Day8/re-frame). Search and annotate the hundreds of jobs listed every month.

[ProTip&trade;: Looking for the progress bar stuff? Scroll all the way down.]

If yer just lookin' for work, the JS version is [live here](https://kennytilton.github.io/whoishiring/) grace a GitHub. Or you can clone this and run it yourself. See below for a devops necessity.

## Development Mode

### Grab HN Pages
The app runs off pages curl'ed straight from the HN server. So they get out of date (but see below for the script I created to run indefinitely at a specified interval to refresh them).

To grab a page we need the message ID of the actual "Who is hiring" question. We start by visiting HN and tracking down the "Who Is Hiring?" question under `AskHN`. Here is [June, 2018](https://news.ycombinator.com/item?id=17205865). Now checkout the browser URL:
````
https://news.ycombinator.com/item?id=17205865
````
The browser app lives and breathes that `17205865`.

Now we want to pull 1-N pages under that ID because, when the list gets big, HN breaks up the page into multiple pages keyed by a simple `p` parameter. Here is what we curl to get page 2:
````
https://news.ycombinator.com/item?id=17205865&p=2
````
To automate page grabbing I cobbled together a neophyte's bash script we invoke like this (but read on for important prior steps):
````bash
cd rehiring
./grab 17205865
````
An optional second parameter tells the script to wait that many minutes and refresh, until control-C'ed.

I mentioned a prior step. There is no way to know how many pages to scrape, but there is no harm other than wasted bandwidth in guessing high: ;f we curl too high a `p` (by setting MAX_P too high) we just get the last. So <sob> I edit the `grab` shell script and look for:
````bash
MAX_P=3
````
...and tweak the 3 (or whatever). Now edit `index.html` and look for this:
````js
    <script>
    var gMonthlies = [
        { hnId: "17205865", desc: "June, 2018", pgCount: 1}
        , { hnId: "16967543", desc: "May, 2018", pgCount: 6}
        , { hnId: "16735011", desc: "April, 2018", pgCount: 5}
    ]
    rehiring.core.init();
   </script>
````
Edit reasonably. The one mistake you can make (blame IFRAME!) is having a `pgCount` higher than the number of pages available: the app will wait for all pages to load but the extras of course will not load and using a JS timer to decide when to give up is left as an exercise. (No, IFRAMEs offer no error to work from if a URL would normally 404.)

Now your copy of the app should work with any new content you specify in `index.html`.

### Run application:

```
lein clean
lein figwheel dev
```

Figwheel will automatically push cljs changes to the browser.

Wait a bit, then browse to [http://localhost:3449](http://localhost:3449).

## Production Build


To compile clojurescript to javascript:

```
lein clean
lein cljsbuild once min
```
## Sidebar: a progress bar animation in re-frame
Parsing eight hundred plus jobs from scratch by loading AskHN HTML pages into an iframe can take a noticeable while, so I thought I should do a progress bar. Then I saw the app on my phone and *knew* I *had* to do a progress bar. That turned out to be quite tricky, in part because declarative functional frameworks have a "steady-state" attitude which is wonderful 95% of the time but not so much for event-exposing functionality. You can see a primitive version of te progress bar from the JS implementation [live here](https://kennytilton.github.io/whoishiring/). The CLJS/re-frame second-generation prorgress bar is much nicer but for now you'll have to run it yourself to see it.

The big enhancement is that you get to see two distinct processes run from start to completion. The first processed N pages to cull DOM nodes, the second processes all the DOM nodes looking for jobs. The neat thing is that the scale changes from 1-6 pages to 0-800+ jobs, and one progress bar smoothly handles both.

Just coding that -- and crucially doing the work in chunks so progress could be reflected in a, well, `progress` element -- was entertaining, but then I could not get the animation to work. Even when I cheated and updated the progress element `max` and `value` properties directly, nothing until the end when we suddenly saw 100% completion. re-frame was not giving control back to the browser until the entire event domino chain had fallen. 

The solution? Two! Throw meta `^:flush_dom` on each event, or use `:dispatch-later` instead of `:dispatch`. Check out the heavily commented source files `month_loader_views.cljs` and `month_loader.cljs` for the deets. 

### Before you ask
Yeah, since the app works off static page grabs that fall out of date anyway, there would be no harm in parsing the pages *once* and saving it all in a big JSON file. I just got bored before then. And now HN has an API on the way that will Change Everything&trade; so we will wait for that. This is just about a progress bar if one is needed.
