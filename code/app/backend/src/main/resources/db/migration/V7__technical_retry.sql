ALTER TABLE "CreditApplicationTask" ADD COLUMN "dispatched" BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE "CreditApplicationTask" SET "dispatched" = TRUE WHERE "attempts" > 0;
