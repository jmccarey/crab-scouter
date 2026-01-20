package com.crabscouter;

import lombok.Data;

@Data
public class WorldData
{
	private int world;
	private int chunk;
	private int health;
	private int totalPlayers;
	private int attackingPlayers;
	private long lastUpdate;

	public WorldData(int world, int chunk, int health, int totalPlayers, int attackingPlayers, long lastUpdate)
	{
		this.world = world;
		this.chunk = chunk;
		this.health = health;
		this.totalPlayers = totalPlayers;
		this.attackingPlayers = attackingPlayers;
		this.lastUpdate = lastUpdate;
	}

	public boolean isFresh()
	{
		return System.currentTimeMillis() - lastUpdate < 90_000;
	}

	public String getChunkName()
	{
		switch (chunk)
		{
			case 4913:
				return "North";
			case 4911:
				return "West";
			case 5424:
				return "East";
			default:
				return "Unknown";
		}
	}
}
