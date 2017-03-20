import * as d3 from "d3";
import {Activity, IdMap} from "./messages";
import {dbgLog} from "./source";

const actorStart = 20; // height at which actor headings are created
const actorHeight = 30; // height of actor headings
const actorWidth = 60; // width of actor headings
const actorSpacing = 100;
const turnRadius = 20;
const turnSpacing = 50; // space between consequent turns

var svgContainer;
var color = ["#3366cc", "#dc3912", "#ff9900", "#109618", "#990099", "#0099c6", "#dd4477", "#66aa00", "#b82e2e", "#316395", "#994499", "#22aa99", "#aaaa11", "#6633cc", "#e67300", "#8b0707", "#651067", "#329262", "#5574a6", "#3b3eac"];

class ActorHeading {
	name: 							string;
	x:          				number;
  y:          				number;
  color: 	  					string;
  turnCount:  				number;
  container: 					d3.Selection<SVGElement>;
  lastTurn: 					turnNode;
  static actorCount: 	number = 0;
  visibility:      		boolean;	

  constructor(name: string) {
  	this.name = name;
  	this.color = color[ActorHeading.actorCount % color.length];
    this.x = 50+ActorHeading.actorCount++*actorSpacing;
    this.y = actorStart;
    this.turnCount = 0;
  }

  addTurn(turn: turnNode){
  	this.lastTurn = turn;
  	return ++this.turnCount;
  }

  changeVisibility() {
  	this.visibility = !this.visibility;
  	if(this.visibility){
  		this.container.style("visibility", "visible");
  	} else {
			this.container.style("visibility", "hidden");
  	}
  }
}

class turnNode {
	x: 			number;
	y: 			number;
	actor: 	ActorHeading;

	constructor(actor: ActorHeading){
		var count = actor.addTurn(this);
		this.actor = actor;
		this.x = actor.x + (actorWidth / 2);
		this.y = actorStart + actorHeight + count * turnSpacing;
	}
} 

export class ProtocolOverview {
	private actors: IdMap<ActorHeading> = {};

	public newActivity(act: Activity){
		switch (act.type) {
	    case "Actor":
	    	var actor = new ActorHeading(act.name);
	    	dbgLog("actor: "+act.name);
	    	this.actors[act.id] = actor;
	    	addActor(actor);
	      break;
	 	}
	}

	public newMessage(senderId: number, targetId: number){
		var sender = this.actors[senderId];
		var target = this.actors[targetId];
		var turn = new turnNode(target);
		addTurn(turn);
		addMessage(sender, turn);
  }

	public constructor(){
		displayProtocolOverview();
	}
}

function displayProtocolOverview() {
  const canvas = $("#protocol-canvas");
  canvas.empty(); // after every restart the canvas needs to be redrawn in case a different program is running on the backend

  svgContainer = d3.select("#protocol-canvas")
    .append("svg")
    .attr("width", canvas.width())
    .attr("height", canvas.height())
    .attr("style", "background: none;");
}

function addActor(actor: ActorHeading){
	var actorHeading = svgContainer.append("g");
	var actorGroup = svgContainer.append("g");
	actor.container = actorGroup;
	// adding text destroys the x coordinates of the actorHeaders
	actorHeading.append("text")
		.attr("x", actor.x+actorWidth/2)
		.attr("y", actor.y+actorHeight/2)
		.attr("font-size","20px")
		.attr("text-anchor", "middle")
		.text(actor.name);		

	actorHeading.append("rect")
		.attr("x", actor.x)
		.attr("y", actor.y)
		.attr("rx", 5)
		.attr("height", actorHeight)
		.attr("width", actorWidth)
		.style("fill", actor.color)
    .style("stroke", actor.color)
    .style("opacity", 0.5)
    .on("click", function(_d,_i){
			actor.changeVisibility();
    });
}

function addTurn(turn: turnNode){
  turn.actor.container.append("circle")
    .attr("cx", turn.x)
    .attr("cy", turn.y)
    .attr("r", turnRadius)
    .style("fill", "none")
    .style("stroke", turn.actor.color);
}

export function addMessage(source: ActorHeading, target: turnNode){
	source.container.append("line")
    .attr("x1", source.lastTurn.x)
    .attr("y1", source.lastTurn.y)
    .attr("x2", target.x)
    .attr("y2", target.y)
    .style("stroke", source.color);
}
