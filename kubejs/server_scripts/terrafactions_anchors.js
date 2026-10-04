// TerraFactions deliberately provides no built-in anchor or war-camp recipes.
// Add or change recipes for the supported anchor tiers and war camp here.
ServerEvents.recipes(event => {
  event.shaped('terrafactions:faction_anchor', [
    'III',
    'ICI',
    'III'
  ], {
    I: 'minecraft:iron_ingot',
    C: 'minecraft:compass'
  }).id('terrafactions:kubejs/faction_anchor')

  event.shaped('terrafactions:advanced_faction_anchor', [
    'DGD',
    'GAG',
    'DGD'
  ], {
    D: 'minecraft:diamond',
    G: 'minecraft:gold_ingot',
    A: 'terrafactions:faction_anchor'
  }).id('terrafactions:kubejs/advanced_faction_anchor')

  event.shaped('terrafactions:master_faction_anchor', [
    'ENE',
    'NAN',
    'ENE'
  ], {
    E: 'minecraft:emerald',
    N: 'minecraft:nether_star',
    A: 'terrafactions:advanced_faction_anchor'
  }).id('terrafactions:kubejs/master_faction_anchor')

  event.shaped('terrafactions:ultimate_faction_anchor', [
    'NBN',
    'BAB',
    'NBN'
  ], {
    N: 'minecraft:netherite_ingot',
    B: 'minecraft:beacon',
    A: 'terrafactions:master_faction_anchor'
  }).id('terrafactions:kubejs/ultimate_faction_anchor')

  event.shaped('terrafactions:war_camp', [
    'GNG',
    'NBN',
    'GNG'
  ], {
    G: 'minecraft:gold_ingot',
    N: 'minecraft:nether_brick',
    B: '#minecraft:banners'
  }).id('terrafactions:kubejs/war_camp')
})
