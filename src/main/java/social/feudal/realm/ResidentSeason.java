package social.feudal.realm;

/** Ration credit is food already taken out of the barrel, in hundredths of a loaf. */
record ResidentSeason(int bread,int credit,int shortfall,int births,int growthRemainder,int deaths,int shortages) {
    static ResidentSeason close(int people,int places,int stock,int credit,int carry,int previousShortages){
        int needed=Math.max(0,people-credit);
        int bread=Math.min(stock,Math.ceilDiv(needed,100));
        int balance=credit+bread*100-people;
        int missing=Math.max(0,-balance);
        int shortages=missing==0?0:previousShortages+1;
        int growth=missing==0 && places>people?Math.addExact(Math.multiplyExact(people,2),carry):0;
        int births=Math.min(Math.max(0,places-people),growth/100);
        int deaths=shortages>=3 && (long)missing*2>people?Math.ceilDiv(missing,20):0;
        return new ResidentSeason(bread,Math.max(0,balance),missing,births,places>people+births?growth%100:0,deaths,shortages);
    }
}
